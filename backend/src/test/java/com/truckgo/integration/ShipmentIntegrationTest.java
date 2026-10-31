package com.truckgo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
class ShipmentIntegrationTest extends IntegrationTestSupport {
    /** Official MinIO images are no longer pullable; Chainguard's build of the same server, pinned by digest. */
    static final String MINIO_IMAGE = "cgr.dev/chainguard/minio@sha256:3b6e40c30ecd5cea627e382ff0b82ca841f58c52294578787043ee80c8ef7c82";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container
    static MinIOContainer minio = new MinIOContainer(DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"));

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.provider", () -> "minio");
        registry.add("app.storage.endpoint", minio::getS3URL);
        registry.add("app.storage.access-key", minio::getUserName);
        registry.add("app.storage.secret-key", minio::getPassword);
        registry.add("app.storage.bucket", () -> "truckgo-test");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;

    // ------------------------------------------------------------------ helpers

    static OffsetDateTime inDays(int days) { return OffsetDateTime.now(ZoneOffset.ofHours(7)).plusDays(days).truncatedTo(ChronoUnit.MINUTES); }

    static Map<String, Object> stop(String type, int seq, String name, String phone, String detail) {
        return new HashMap<>(Map.of("stopType", type, "sequenceNo", seq, "contactName", name, "contactPhone", phone, "province", "Hà Nội",
                "district", "Cầu Giấy", "ward", "Dịch Vọng", "addressDetail", detail));
    }

    static Map<String, Object> item(String name, String category, int qty, String weight, String l, String w, String h) {
        Map<String, Object> item = new HashMap<>(Map.of("itemName", name, "category", category, "quantity", qty, "weightPerUnitKg", weight));
        if (l != null) { item.put("lengthCm", l); item.put("widthCm", w); item.put("heightCm", h); }
        return item;
    }

    static Map<String, Object> draft() {
        Map<String, Object> pickup = stop("PICKUP", 1, "Nguyen Van A", "0912345678", "Số 10, đường X");
        pickup.put("latitude", 21.0285);
        pickup.put("longitude", 105.8542);
        Map<String, Object> body = new HashMap<>();
        body.put("pickupTime", inDays(3).toString());
        body.put("deliveryDeadline", inDays(3).plusHours(10).toString());
        body.put("vehicleTypeCode", "TRUCK_1T");
        body.put("customerNote", "Gọi trước khi đến lấy hàng");
        body.put("stops", List.of(pickup, stop("DELIVERY", 2, "Nguyen Van B", "0987654321", "Số 20, đường Y")));
        body.put("items", List.of(item("Thùng hàng điện tử", "ELECTRONICS", 10, "20", "50", "40", "30")));
        body.put("services", List.of(Map.of("serviceCode", "FRAGILE_GOODS", "serviceValue", "true")));
        return body;
    }

    Session customer() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        return login(email, PASSWORD);
    }

    ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, Session session, Object body) throws Exception {
        builder.header("Authorization", bearer(session));
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(builder);
    }

    JsonNode createDraft(Session session, Map<String, Object> body) throws Exception {
        return body(send(post("/api/v1/shipments/draft"), session, body).andExpect(status().isCreated()).andReturn()).get("data");
    }

    ResultActions upload(Session session, String shipmentId, byte[] bytes, String name, String declaredType) throws Exception {
        return mvc.perform(multipart("/api/v1/shipments/" + shipmentId + "/attachments").file(new MockMultipartFile("file", name, declaredType, bytes))
                .header("Authorization", bearer(session)));
    }

    MinioClient minioClient() { return MinioClient.builder().endpoint(minio.getS3URL()).credentials(minio.getUserName(), minio.getPassword()).build(); }

    // ------------------------------------------------------------------ tests

    @Test void flywayAppliedV3AndSeededCatalogs() throws Exception {
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class)).containsExactly("1", "2", "3");
        Session session = customer();
        send(get("/api/v1/catalogs/vehicle-types"), session, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10)).andExpect(jsonPath("$.data[0].code").value("TRUCK_500KG"));
        send(get("/api/v1/catalogs/item-categories"), session, null).andExpect(jsonPath("$.data.length()").value(10));
        send(get("/api/v1/catalogs/shipment-services"), session, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code=='LOADING_SUPPORT')].options.length()").value(3));
        send(get("/api/v1/catalogs/vehicle-body-types"), session, null).andExpect(status().isOk());
        mvc.perform(get("/api/v1/catalogs/vehicle-types")).andExpect(status().isUnauthorized());
    }

    @Test void catalogIsCachedInRedisAndEvictedWhenAdminChangesIt() throws Exception {
        Session session = customer();
        Session admin = newAdmin();
        send(get("/api/v1/catalogs/item-categories"), session, null).andExpect(status().isOk());
        assertThat(redisTemplate.hasKey("catalog:v1:item-categories")).isTrue();
        send(patch("/api/v1/admin/catalogs/item-categories/food"), session, Map.of("active", false)).andExpect(status().isForbidden());
        send(patch("/api/v1/admin/catalogs/item-categories/food"), admin, Map.of("active", false)).andExpect(status().isOk());
        assertThat(redisTemplate.hasKey("catalog:v1:item-categories")).isFalse();
        send(get("/api/v1/catalogs/item-categories"), session, null).andExpect(jsonPath("$.data[?(@.code=='FOOD')]").isEmpty());
        Map<String, Object> body = draft();
        body.put("items", List.of(item("Rau", "FOOD", 1, "5", null, null, null)));
        send(post("/api/v1/shipments/draft"), session, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data['items[0].category']").exists());
        send(patch("/api/v1/admin/catalogs/item-categories/FOOD"), admin, Map.of("active", true)).andExpect(status().isOk());
        send(get("/api/v1/catalogs/item-categories"), session, null).andExpect(jsonPath("$.data[?(@.code=='FOOD')]").isNotEmpty());
    }

    @Test void createsDraftWithServerComputedTotalsAndIgnoresClientTotals() throws Exception {
        Session session = customer();
        Map<String, Object> body = draft();
        body.put("items", List.of(item("Thùng hàng A", "CONSUMER_GOODS", 10, "20", "50", "40", "30"), item("Bàn làm việc", "FURNITURE", 2, "35", "120", "60", "75"),
                item("Tủ lạnh", "ELECTRONICS", 1, "60", "70", "70", "180")));
        body.put("totalWeightKg", 1);
        body.put("totalVolumeM3", 0.001);
        body.put("customerId", UUID.randomUUID().toString());
        body.put("status", "PUBLISHED");
        body.put("estimatedPrice", 999);
        JsonNode d = createDraft(session, body);
        assertThat(d.get("shipmentCode").asText()).matches("TG\\d{6}-[2-9A-HJKMNP-Z]{6}");
        assertThat(d.get("status").asText()).isEqualTo("DRAFT");
        assertThat(d.get("createdAt").isNull()).as("timestamps are returned on create").isFalse();
        assertThat(d.get("updatedAt").isNull()).isFalse();
        assertThat(d.get("customerId").asText()).isEqualTo(session.user().get("id").asText());
        assertThat(d.get("totalQuantity").asInt()).isEqualTo(13);
        assertThat(d.get("totalWeightKg").decimalValue()).isEqualByComparingTo("330");
        assertThat(d.get("totalVolumeM3").decimalValue()).isEqualByComparingTo("2.562");
        assertThat(d.get("priceStatus").asText()).isEqualTo("NOT_QUOTED");
        assertThat(d.get("estimatedPrice").isNull()).isTrue();
        assertThat(d.at("/pickup/contactPhone").asText()).isEqualTo("+84912345678");
        assertThat(d.at("/delivery/fullAddress").asText()).isEqualTo("Số 20, đường Y, Dịch Vọng, Cầu Giấy, Hà Nội");
        assertThat(d.at("/delivery/latitude").isNull()).as("no coordinates are invented").isTrue();
        assertThat(d.at("/services/0/serviceName").asText()).isEqualTo("Hàng dễ vỡ");
        assertThat(d.at("/statusHistory/0/newStatus").asText()).isEqualTo("DRAFT");
        assertThat(d.get("warnings").findValuesAsText("code")).isEmpty();
        assertThat(jdbc.queryForObject("select total_weight_kg::text from shipments where id = ?::uuid", String.class, d.get("id").asText())).isEqualTo("330.00");
    }

    @Test void draftRequiresPickupDeliveryAndItems() throws Exception {
        Session session = customer();
        Map<String, Object> noPickup = draft();
        noPickup.put("stops", List.of(stop("DELIVERY", 1, "B", "0987654321", "X"), stop("DELIVERY", 2, "C", "0987654322", "Y")));
        send(post("/api/v1/shipments/draft"), session, noPickup).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.stops").value("Cần ít nhất một điểm lấy hàng"));
        Map<String, Object> noDelivery = draft();
        noDelivery.put("stops", List.of(stop("PICKUP", 1, "A", "0912345678", "X"), stop("PICKUP", 2, "C", "0912345679", "Y")));
        send(post("/api/v1/shipments/draft"), session, noDelivery).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.stops").value("Cần ít nhất một điểm giao hàng"));
        Map<String, Object> noItems = draft();
        noItems.put("items", List.of());
        send(post("/api/v1/shipments/draft"), session, noItems).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.items").exists());
        Map<String, Object> badCoords = draft();
        Map<String, Object> pickup = stop("PICKUP", 1, "A", "0912345678", "X");
        pickup.put("latitude", 91);
        pickup.put("longitude", 105);
        badCoords.put("stops", List.of(pickup, stop("DELIVERY", 2, "B", "0987654321", "Y")));
        send(post("/api/v1/shipments/draft"), session, badCoords).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data['stops[0].latitude']").exists());
        assertThat(jdbc.queryForObject("select count(*) from shipments where customer_id = ?::uuid", Integer.class, session.user().get("id").asText())).isZero();
    }

    @Test void customersCannotSeeOrChangeEachOthersOrders() throws Exception {
        Session a = customer();
        Session b = customer();
        JsonNode order = createDraft(a, draft());
        String id = order.get("id").asText();
        send(get("/api/v1/shipments/" + id), b, null).andExpect(status().isNotFound());
        Map<String, Object> update = draft();
        update.put("version", order.get("version").asLong());
        send(put("/api/v1/shipments/" + id + "/draft"), b, update).andExpect(status().isNotFound());
        send(post("/api/v1/shipments/" + id + "/publish"), b, null).andExpect(status().isNotFound());
        send(post("/api/v1/shipments/" + id + "/cancel"), b, Map.of("reason", "Không phải đơn của tôi")).andExpect(status().isNotFound());
        send(delete("/api/v1/shipments/" + id), b, null).andExpect(status().isNotFound());
        send(get("/api/v1/shipments/" + id + "/attachments"), b, null).andExpect(status().isNotFound());
        send(get("/api/v1/shipments/my"), b, null).andExpect(jsonPath("$.data.totalElements").value(0));
        send(get("/api/v1/shipments/" + id), a, null).andExpect(status().isOk());

        String driverEmail = uniqueEmail();
        registerDriver(driverEmail);
        Session driver = login(driverEmail, PASSWORD);
        send(get("/api/v1/shipments/" + id), driver, null).andExpect(status().isForbidden());
        send(post("/api/v1/shipments/draft"), driver, draft()).andExpect(status().isForbidden());

        Session admin = newAdmin();
        send(get("/api/v1/shipments/" + id), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.actions.editable").value(false));
        send(post("/api/v1/shipments/" + id + "/publish"), admin, null).andExpect(status().isForbidden());
        send(get("/api/v1/admin/shipments?keyword=" + order.get("shipmentCode").asText()), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        send(get("/api/v1/admin/shipments"), a, null).andExpect(status().isForbidden());
    }

    @Test void publishFlowAndStatusRules() throws Exception {
        Session session = customer();
        Map<String, Object> incomplete = draft();
        incomplete.remove("pickupTime");
        incomplete.remove("deliveryDeadline");
        incomplete.put("items", List.of(item("Bao gạo", "AGRICULTURAL", 5, "50", null, null, null)));
        JsonNode draft = createDraft(session, incomplete);
        String id = draft.get("id").asText();
        assertThat(draft.get("warnings").findValuesAsText("code")).contains("DIMENSIONS_MISSING");
        send(post("/api/v1/shipments/" + id + "/publish"), session, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.pickupTime").exists()).andExpect(jsonPath("$.data['items[0].lengthCm']").exists());

        Map<String, Object> complete = draft();
        complete.put("version", draft.get("version").asLong());
        send(put("/api/v1/shipments/" + id + "/draft"), session, complete).andExpect(status().isOk());
        JsonNode published = body(send(post("/api/v1/shipments/" + id + "/publish"), session, null).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(published.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.get("publishedAt").isNull()).isFalse();
        assertThat(published.get("estimatedPrice").isNull()).isTrue();
        send(post("/api/v1/shipments/" + id + "/publish"), session, null).andExpect(status().isConflict());
        Map<String, Object> edit = draft();
        edit.put("version", published.get("version").asLong());
        send(put("/api/v1/shipments/" + id + "/draft"), session, edit).andExpect(status().isConflict());
        send(delete("/api/v1/shipments/" + id), session, null).andExpect(status().isConflict());
        upload(session, id, PNG, "a.png", "image/png").andExpect(status().isConflict());

        send(post("/api/v1/shipments/" + id + "/cancel"), session, Map.of("reason", "x")).andExpect(status().isBadRequest());
        JsonNode cancelled = body(send(post("/api/v1/shipments/" + id + "/cancel"), session, Map.of("reason", "Khách hàng thay đổi kế hoạch"))
                .andExpect(status().isOk()).andReturn()).get("data");
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancellationReason").asText()).isEqualTo("Khách hàng thay đổi kế hoạch");
        send(post("/api/v1/shipments/" + id + "/publish"), session, null).andExpect(status().isConflict());
        send(post("/api/v1/shipments/" + id + "/cancel"), session, Map.of("reason", "Hủy lần nữa")).andExpect(status().isConflict());

        String customerId = session.user().get("id").asText();
        List<Map<String, Object>> history = jdbc.queryForList(
                "select old_status, new_status, changed_by::text as changed_by, change_reason from shipment_status_histories where shipment_id = ?::uuid order by created_at", id);
        assertThat(history).extracting(h -> h.get("old_status") + ">" + h.get("new_status")).containsExactly("null>DRAFT", "DRAFT>PUBLISHED", "PUBLISHED>CANCELLED");
        assertThat(history).allSatisfy(h -> assertThat(h.get("changed_by")).isEqualTo(customerId));
        assertThat(history.get(2).get("change_reason")).isEqualTo("Khách hàng thay đổi kế hoạch");
    }

    @Test void completedOrderCannotBeCancelled() throws Exception {
        Session session = customer();
        String id = createDraft(session, draft()).get("id").asText();
        send(post("/api/v1/shipments/" + id + "/publish"), session, null).andExpect(status().isOk());
        jdbc.update("update shipments set status = 'COMPLETED' where id = ?::uuid", id);
        send(post("/api/v1/shipments/" + id + "/cancel"), session, Map.of("reason", "Muốn hủy đơn đã xong")).andExpect(status().isConflict());
        jdbc.update("update shipments set status = 'ASSIGNED' where id = ?::uuid", id);
        send(post("/api/v1/shipments/" + id + "/cancel"), session, Map.of("reason", "Tài xế đã nhận rồi")).andExpect(status().isConflict());
    }

    @Test void updatingDraftReplacesDetailsButKeepsAttachments() throws Exception {
        Session session = customer();
        JsonNode created = createDraft(session, draft());
        String id = created.get("id").asText();
        upload(session, id, PNG, "hang.png", "image/png").andExpect(status().isCreated());

        Map<String, Object> update = draft();
        update.put("version", created.get("version").asLong());
        update.put("stops", List.of(stop("PICKUP", 1, "Kho A", "0912345678", "Kho 1"), stop("DELIVERY", 2, "Cửa hàng 1", "0987654321", "Số 1"),
                stop("DELIVERY", 3, "Cửa hàng 2", "0987654322", "Số 2")));
        update.put("items", List.of(item("Tủ lạnh", "ELECTRONICS", 2, "60", "70", "70", "180")));
        update.put("services", List.of(Map.of("serviceCode", "LOADING_SUPPORT", "serviceValue", "TWO_PEOPLE"), Map.of("serviceCode", "MULTI_STOP_DELIVERY", "serviceValue", "true")));
        update.put("vehicleTypeCode", "TRUCK_500KG");
        JsonNode updated = body(send(put("/api/v1/shipments/" + id + "/draft"), session, update).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(updated.get("stops").size()).isEqualTo(3);
        assertThat(updated.get("items").size()).isEqualTo(1);
        assertThat(updated.get("totalWeightKg").decimalValue()).isEqualByComparingTo("120");
        assertThat(updated.get("totalVolumeM3").decimalValue()).isEqualByComparingTo("1.764");
        assertThat(updated.get("services").findValuesAsText("serviceCode")).containsExactly("LOADING_SUPPORT", "MULTI_STOP_DELIVERY");
        assertThat(updated.get("attachments").size()).as("attachments survive a draft update").isEqualTo(1);
        assertThat(updated.at("/delivery/contactName").asText()).isEqualTo("Cửa hàng 2");
        assertThat(jdbc.queryForObject("select count(*) from shipment_stops where shipment_id = ?::uuid", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from shipment_items where shipment_id = ?::uuid", Integer.class, id)).isEqualTo(1);
        // Same draft again with the old version: rejected instead of silently overwriting.
        send(put("/api/v1/shipments/" + id + "/draft"), session, update).andExpect(status().isConflict());
        send(get("/api/v1/shipments/my?keyword=" + created.get("shipmentCode").asText()), session, null)
                .andExpect(jsonPath("$.data.items[0].deliveryStopCount").value(2));
    }

    @Test void timesAreStoredInUtcAndDateFiltersUseVietnamTime() throws Exception {
        Session session = customer();
        Map<String, Object> body = draft();
        OffsetDateTime pickup = inDays(5).withHour(8).withMinute(0);
        body.put("pickupTime", pickup.toString());
        body.put("deliveryDeadline", pickup.plusHours(10).toString());
        JsonNode d = createDraft(session, body);
        assertThat(OffsetDateTime.parse(d.get("pickupTime").asText()).toInstant()).isEqualTo(pickup.toInstant());
        assertThat(d.get("pickupTime").asText()).endsWith("Z").contains("T01:00:00");

        String id = d.get("id").asText();
        // 2026-03-10T18:00Z is 2026-03-11 01:00 in Vietnam.
        jdbc.update("update shipments set created_at = '2026-03-10T18:00:00Z' where id = ?::uuid", id);
        send(get("/api/v1/shipments/my?fromDate=2026-03-11&toDate=2026-03-11"), session, null).andExpect(jsonPath("$.data.totalElements").value(1));
        send(get("/api/v1/shipments/my?fromDate=2026-03-10&toDate=2026-03-10"), session, null).andExpect(jsonPath("$.data.totalElements").value(0));
        send(get("/api/v1/shipments/my?fromDate=2026-03-12&toDate=2026-03-11"), session, null).andExpect(status().isBadRequest());

        Map<String, Object> inverted = draft();
        inverted.put("deliveryDeadline", inDays(1).toString());
        inverted.put("pickupTime", inDays(2).toString());
        send(post("/api/v1/shipments/draft"), session, inverted).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.deliveryDeadline").exists());
    }

    @Test void listSupportsFilteringSortingAndPagination() throws Exception {
        Session session = customer();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 3; i++) codes.add(createDraft(session, draft()).get("shipmentCode").asText());
        String publishedId = createDraft(session, draft()).get("id").asText();
        send(post("/api/v1/shipments/" + publishedId + "/publish"), session, null).andExpect(status().isOk());

        JsonNode page = body(send(get("/api/v1/shipments/my?page=0&size=2"), session, null).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(page.get("totalElements").asInt()).isEqualTo(4);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.at("/items/0/id").asText()).as("newest first by default").isEqualTo(publishedId);
        send(get("/api/v1/shipments/my?status=PUBLISHED"), session, null).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].pickupAddress").value("Số 10, đường X, Dịch Vọng, Cầu Giấy, Hà Nội"));
        send(get("/api/v1/shipments/my?keyword=" + codes.get(1).toLowerCase()), session, null).andExpect(jsonPath("$.data.totalElements").value(1));
        send(get("/api/v1/shipments/my?keyword=đường Y"), session, null).andExpect(jsonPath("$.data.totalElements").value(4));
        send(get("/api/v1/shipments/my?keyword=100%25"), session, null).andExpect(jsonPath("$.data.totalElements").value(0));
        JsonNode asc = body(send(get("/api/v1/shipments/my?sortBy=shipmentCode&sortDirection=asc&size=10"), session, null).andReturn()).get("data");
        List<String> sorted = asc.get("items").findValuesAsText("shipmentCode");
        assertThat(sorted).isSorted();
        send(get("/api/v1/shipments/my?sortBy=customerId"), session, null).andExpect(status().isBadRequest());
        send(get("/api/v1/shipments/my?status=FLYING"), session, null).andExpect(status().isBadRequest());
    }

    @Test void deletingDraftIsSoftAndRemovesPhotos() throws Exception {
        Session session = customer();
        String id = createDraft(session, draft()).get("id").asText();
        upload(session, id, PNG, "hang.png", "image/png").andExpect(status().isCreated());
        String key = jdbc.queryForObject("select file_key from shipment_attachments where shipment_id = ?::uuid", String.class, id);
        send(delete("/api/v1/shipments/" + id), session, null).andExpect(status().isOk());
        send(get("/api/v1/shipments/" + id), session, null).andExpect(status().isNotFound());
        send(get("/api/v1/shipments/my"), session, null).andExpect(jsonPath("$.data.totalElements").value(0));
        assertThat(jdbc.queryForObject("select deleted_at is not null from shipments where id = ?::uuid", Boolean.class, id)).isTrue();
        assertThatThrownBy(() -> minioClient().statObject(StatObjectArgs.builder().bucket("truckgo-test").object(key).build()))
                .hasMessageContaining("Object does not exist");
    }

    @Test void attachmentsAreStoredPrivatelyAndValidated() throws Exception {
        Session owner = customer();
        Session other = customer();
        String id = createDraft(owner, draft()).get("id").asText();

        JsonNode uploaded = body(upload(owner, id, PNG, "../../etc/tủ lạnh.png", "application/octet-stream").andExpect(status().isCreated()).andReturn()).get("data");
        assertThat(uploaded.get("contentType").asText()).as("type comes from the bytes").isEqualTo("image/png");
        assertThat(uploaded.get("fileName").asText()).isEqualTo("tủ lạnh.png");
        assertThat(uploaded.get("contentUrl").asText()).startsWith("/api/v1/shipments/" + id + "/attachments/").doesNotContain("minio").doesNotContain("truckgo-test");
        String key = jdbc.queryForObject("select file_key from shipment_attachments where id = ?::uuid", String.class, uploaded.get("id").asText());
        assertThat(key).matches("shipments/" + id + "/[0-9a-f-]{36}\\.png");
        assertThat(minioClient().statObject(StatObjectArgs.builder().bucket("truckgo-test").object(key).build()).size()).isEqualTo(PNG.length);

        mvc.perform(get(uploaded.get("contentUrl").asText()).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get(uploaded.get("contentUrl").asText()).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get(uploaded.get("contentUrl").asText())).andExpect(status().isUnauthorized());

        upload(owner, id, "<svg onload=alert(1)>".getBytes(), "hang.png", "image/png").andExpect(status().isUnsupportedMediaType());
        upload(owner, id, "%PDF-1.7".getBytes(), "hang.pdf", "application/pdf").andExpect(status().isUnsupportedMediaType());
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        upload(owner, id, big, "big.png", "image/png").andExpect(status().isPayloadTooLarge());
        upload(other, id, PNG, "x.png", "image/png").andExpect(status().isNotFound());

        send(delete("/api/v1/shipments/" + id + "/attachments/" + uploaded.get("id").asText()), other, null).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from shipment_attachments where shipment_id = ?::uuid", Integer.class, id)).isEqualTo(1);
        send(delete("/api/v1/shipments/" + id + "/attachments/" + uploaded.get("id").asText()), owner, null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from shipment_attachments where shipment_id = ?::uuid", Integer.class, id)).isZero();
        assertThatThrownBy(() -> minioClient().statObject(StatObjectArgs.builder().bucket("truckgo-test").object(key).build()))
                .hasMessageContaining("Object does not exist");
    }

    @Test void databaseEnforcesShipmentConstraints() throws Exception {
        Session session = customer();
        JsonNode d = createDraft(session, draft());
        String customerId = session.user().get("id").asText();
        String insert = "insert into shipments (id, shipment_code, customer_id, status, created_at, updated_at) values (?::uuid, ?, ?::uuid, 'DRAFT', now(), now())";
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), d.get("shipmentCode").asText(), customerId))
                .as("shipment_code is unique").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update shipments set status = 'PUBLISHED', published_at = now(), pickup_time = now() - interval '1 hour' where id = ?::uuid", d.get("id").asText()))
                .as("pickup cannot be in the past when published").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update shipment_items set quantity = 0 where shipment_id = ?::uuid", d.get("id").asText()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update shipment_stops set latitude = 95, longitude = 10 where shipment_id = ?::uuid", d.get("id").asText()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update shipments set total_weight_kg = -1 where id = ?::uuid", d.get("id").asText()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void warnsWhenRequestedVehicleLooksTooSmall() throws Exception {
        Session session = customer();
        Map<String, Object> body = draft();
        body.put("vehicleTypeCode", "TRUCK_500KG");
        body.put("minimumPayloadKg", 100);
        body.put("items", List.of(item("Tủ lạnh", "ELECTRONICS", 10, "60", "70", "70", "250")));
        JsonNode d = createDraft(session, body);
        assertThat(d.get("warnings").findValuesAsText("code"))
                .contains("VEHICLE_PAYLOAD_INSUFFICIENT", "MINIMUM_PAYLOAD_BELOW_WEIGHT", "VOLUME_EXCEEDS_CARGO", "ITEM_EXCEEDS_CARGO_SIZE");
        // Advisory only: the order is still saved and can be published.
        send(post("/api/v1/shipments/" + d.get("id").asText() + "/publish"), session, null).andExpect(status().isOk());
    }
}
