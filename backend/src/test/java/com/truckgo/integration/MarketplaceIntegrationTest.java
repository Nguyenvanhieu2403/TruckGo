package com.truckgo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.truckgo.common.exception.BusinessRuleException;
import com.truckgo.marketplace.CustomerApplicationService;
import com.truckgo.marketplace.MarketplaceLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@org.springframework.context.annotation.Import(PricingIntegrationTest.RouteStub.class)
class MarketplaceIntegrationTest extends IntegrationTestSupport {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final String VEHICLE_TYPE = "TRUCK_1T";

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired CustomerApplicationService customerApplications;
    @Autowired MarketplaceLifecycle lifecycle;

    Session admin;

    @BeforeEach void setUp() throws Exception {
        admin = newAdmin();
        if (jdbc.queryForObject("select count(*) from pricing_configs where vehicle_type_code = ? and is_active", Long.class, VEHICLE_TYPE) == 0) {
            Map<String, Object> c = new HashMap<>();
            c.put("name", "Bảng giá Marketplace"); c.put("vehicleTypeCode", VEHICLE_TYPE);
            c.put("basePrice", "100000"); c.put("pricePerKm", "8000"); c.put("includedDistanceKm", "0");
            c.put("includedWeightKg", "500"); c.put("pricePerExtraKg", "500"); c.put("includedVolumeM3", "5"); c.put("pricePerExtraM3", "40000");
            c.put("additionalStopPrice", "20000"); c.put("minimumPrice", "0");
            c.put("effectiveFrom", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString());
            send(post("/api/v1/admin/pricing-configs"), admin, c).andExpect(status().isCreated());
        }
    }

    // ------------------------------------------------------------------ helpers

    ResultActions send(MockHttpServletRequestBuilder builder, Session session, Object body) throws Exception {
        builder.header("Authorization", bearer(session));
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(builder);
    }

    JsonNode data(ResultActions actions) throws Exception { return body(actions.andReturn()).get("data"); }

    Session customer() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        return login(email, PASSWORD);
    }

    static Map<String, Object> stop(String type, int seq, String province, String district) {
        return new HashMap<>(Map.of("stopType", type, "sequenceNo", seq, "contactName", "Liên hệ " + seq, "contactPhone", "091234567" + seq,
                "province", province, "district", district, "addressDetail", "Số " + seq + " ngõ bí mật"));
    }

    /** A published order for one 1-tonne truck: 600 kg of washing machines from Hà Nội to Hải Phòng, quote accepted. */
    String openShipment(Session customer, boolean acceptQuote) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("pickupTime", OffsetDateTime.now(ZoneOffset.ofHours(7)).plusDays(2).withHour(10).truncatedTo(ChronoUnit.HOURS).toString());
        body.put("vehicleTypeCode", VEHICLE_TYPE);
        body.put("stops", List.of(stop("PICKUP", 1, "Hà Nội", "Cầu Giấy"), stop("DELIVERY", 2, "Hải Phòng", "Lê Chân")));
        body.put("items", List.of(Map.of("itemName", "Máy giặt", "category", "ELECTRONICS", "quantity", 10, "weightPerUnitKg", "60",
                "lengthCm", "60", "widthCm", "60", "heightCm", "90")));
        String id = data(send(post("/api/v1/shipments/draft"), customer, body).andExpect(status().isCreated())).get("id").asText();
        if (acceptQuote) {
            send(post("/api/v1/shipments/" + id + "/pricing/preview"), customer, null).andExpect(status().isOk());
            send(post("/api/v1/shipments/" + id + "/pricing/accept"), customer, null).andExpect(status().isOk());
        }
        send(post("/api/v1/shipments/" + id + "/publish"), customer, null).andExpect(status().isOk());
        return id;
    }

    ResultActions apply(DriverFixture driver, String shipmentId, String vehicleId) throws Exception {
        return send(post("/api/v1/driver/marketplace/shipments/" + shipmentId + "/applications"), driver.session(),
                Map.of("vehicleId", vehicleId, "driverMessage", "Xe sẵn sàng"));
    }

    String applicationId(DriverFixture driver, String shipmentId) throws Exception {
        return data(apply(driver, shipmentId, driver.vehicleId()).andExpect(status().isCreated())).get("id").asText();
    }

    List<String> marketplaceIds(Session driver, String query) throws Exception {
        JsonNode items = data(send(get("/api/v1/driver/marketplace/shipments?size=50&" + query), driver, null).andExpect(status().isOk())).get("items");
        List<String> ids = new ArrayList<>();
        items.forEach(i -> ids.add(i.at("/shipment/id").asText()));
        return ids;
    }

    List<String> notificationTypes(Session session) throws Exception {
        List<String> types = new ArrayList<>();
        data(send(get("/api/v1/notifications?size=100"), session, null).andExpect(status().isOk())).get("items").forEach(n -> types.add(n.get("type").asText()));
        return types;
    }

    // ------------------------------------------------------------------ tests

    @Test void driverFindsAppliesAndCustomerChoosesOneDriver() throws Exception {
        Session customer = customer();
        String id = openShipment(customer, true);
        DriverFixture a = approvedDriver(admin, Map.of());
        DriverFixture b = approvedDriver(admin, Map.of());

        // Accent-insensitive filters, price/weight ranges and sorting.
        assertThat(marketplaceIds(a.session(), "pickupProvince=ha noi&deliveryProvince=HAI PHONG&vehicleTypeId=" + VEHICLE_TYPE)).contains(id);
        assertThat(marketplaceIds(a.session(), "keyword=may giat&minWeight=600&maxWeight=600&sortBy=price&sortDirection=desc")).contains(id);
        assertThat(marketplaceIds(a.session(), "pickupProvince=Đà Nẵng")).doesNotContain(id);
        assertThat(marketplaceIds(a.session(), "maxPrice=1000")).doesNotContain(id);
        send(get("/api/v1/driver/marketplace/shipments?sortBy=password"), a.session(), null).andExpect(status().isBadRequest());

        // Before assignment the driver sees the area, never the exact address or contact.
        JsonNode detail = data(send(get("/api/v1/driver/marketplace/shipments/" + id), a.session(), null).andExpect(status().isOk()));
        assertThat(detail.get("canApply").asBoolean()).isTrue();
        assertThat(detail.get("price").decimalValue()).isPositive();
        assertThat(detail.at("/stops/0/province").asText()).isEqualTo("Hà Nội");
        assertThat(detail.at("/stops/0/fullAddress").isNull()).isTrue();
        assertThat(detail.at("/stops/0/contactPhone").isNull()).isTrue();
        assertThat(detail.at("/vehicles/0/eligible").asBoolean()).isTrue();

        String appA = applicationId(a, id);
        String appB = applicationId(b, id);
        apply(a, id, a.vehicleId()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("ALREADY_APPLIED"));
        assertThat(notificationTypes(customer)).containsOnly("APPLICATION_RECEIVED").hasSize(2);
        assertThat(data(send(get("/api/v1/driver/marketplace/shipments?size=50"), a.session(), null)).get("items").findValuesAsText("myApplicationStatus")).contains("PENDING");

        JsonNode applicants = data(send(get("/api/v1/customer/shipments/" + id + "/applications"), customer, null).andExpect(status().isOk()));
        assertThat(applicants).hasSize(2);
        assertThat(applicants.get(0).at("/driver/phone").isNull()).isTrue();
        assertThat(applicants.get(0).at("/driver/completedTrips").asLong()).isZero();
        assertThat(applicants.get(0).get("vehicleEligible").asBoolean()).isTrue();

        send(post("/api/v1/customer/shipments/" + id + "/applications/" + appA + "/accept"), customer, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED")).andExpect(jsonPath("$.data.driver.fullName").value("Nguyen Van B"));

        JsonNode shipment = data(send(get("/api/v1/shipments/" + id), customer, null));
        assertThat(shipment.get("status").asText()).isEqualTo("ASSIGNED");
        assertThat(shipment.at("/assignment/driverId").asText()).isEqualTo(a.profileId());
        assertThat(shipment.at("/assignment/applicationId").asText()).isEqualTo(appA);
        assertThat(shipment.at("/actions/cancellable").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select status from driver_shipment_applications where id = ?::uuid", String.class, appB)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select count(*) from shipment_assignment_histories where shipment_id = ?::uuid", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from shipment_status_histories where shipment_id = ?::uuid and new_status = 'ASSIGNED'", Long.class, id)).isEqualTo(1);
        assertThat(notificationTypes(a.session())).containsExactly("APPLICATION_ACCEPTED");
        assertThat(notificationTypes(b.session())).containsExactly("APPLICATION_REJECTED");

        // The chosen driver now sees the exact addresses and contacts; the other one does not.
        JsonNode mine = data(send(get("/api/v1/driver/applications/" + appA), a.session(), null).andExpect(status().isOk()));
        assertThat(mine.at("/stops/0/fullAddress").asText()).contains("ngõ bí mật");
        assertThat(mine.at("/stops/0/contactPhone").asText()).isNotEmpty();
        JsonNode theirs = data(send(get("/api/v1/driver/applications/" + appB), b.session(), null).andExpect(status().isOk()));
        assertThat(theirs.at("/stops/0/fullAddress").isNull()).isTrue();
        assertThat(theirs.get("responseReason").asText()).isEqualTo("Khách hàng đã chọn tài xế khác");

        // The order is gone from the marketplace and nothing can be added or changed any more.
        assertThat(marketplaceIds(a.session(), "")).doesNotContain(id);
        send(post("/api/v1/customer/shipments/" + id + "/applications/" + appB + "/accept"), customer, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("SHIPMENT_ALREADY_ASSIGNED"));
        DriverFixture late = approvedDriver(admin, Map.of());
        apply(late, id, late.vehicleId()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("SHIPMENT_ALREADY_ASSIGNED"));
        send(post("/api/v1/driver/applications/" + appA + "/withdraw"), a.session(), Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("APPLICATION_NOT_PENDING"));
        send(post("/api/v1/shipments/" + id + "/cancel"), customer, Map.of("reason", "Đổi ý")).andExpect(status().isConflict());
    }

    @Test void concurrentAcceptsAssignExactlyOneDriver() throws Exception {
        Session customer = customer();
        String id = openShipment(customer, true);
        UUID customerId = UUID.fromString(customer.user().get("id").asText());
        List<String> apps = new ArrayList<>();
        for (int i = 0; i < 6; i++) apps.add(applicationId(approvedDriver(admin, Map.of()), id));

        ExecutorService pool = Executors.newFixedThreadPool(apps.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (String app : apps) {
            Callable<String> task = () -> {
                start.await();
                try {
                    customerApplications.accept(customerId, UUID.fromString(id), UUID.fromString(app));
                    return "OK";
                } catch (BusinessRuleException ex) {
                    return ex.code();
                }
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) outcomes.add(f.get());
        pool.shutdown();

        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.equals("OK")).allMatch(o -> o.equals("SHIPMENT_ALREADY_ASSIGNED") || o.equals("APPLICATION_NOT_PENDING"));
        assertThat(jdbc.queryForObject("select count(*) from driver_shipment_applications where shipment_id = ?::uuid and status = 'ACCEPTED'", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from driver_shipment_applications where shipment_id = ?::uuid and status = 'REJECTED'", Long.class, id)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from shipment_assignment_histories where shipment_id = ?::uuid", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from shipments where id = ?::uuid", String.class, id)).isEqualTo("ASSIGNED");
    }

    @Test void onlyEligibleDriversAndVehiclesCanApply() throws Exception {
        Session customer = customer();
        String id = openShipment(customer, true);

        // Driver profile not approved yet.
        String email = uniqueEmail();
        registerDriver(email);
        Session pendingDriver = login(email, PASSWORD);
        send(post("/api/v1/driver/marketplace/shipments/" + id + "/applications"), pendingDriver, Map.of("vehicleId", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.code").value("DRIVER_NOT_ELIGIBLE"));
        assertThat(data(send(get("/api/v1/driver/marketplace/shipments/" + id), pendingDriver, null)).get("canApply").asBoolean()).isFalse();

        // Vehicle too small for a 1-tonne order (500 kg is the class below) and one whose registration expires first.
        DriverFixture driver = approvedDriver(admin, Map.of("maxPayloadKg", 500));
        apply(driver, id, driver.vehicleId()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.code").value("VEHICLE_NOT_ELIGIBLE")).andExpect(jsonPath("$.data.reasons").isArray());
        String expiring = addApprovedVehicle(admin, driver.session(), Map.of("registrationExpiryDate", java.time.LocalDate.now().plusDays(1).toString()));
        apply(driver, id, expiring).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.code").value("VEHICLE_NOT_ELIGIBLE"));

        // Someone else's vehicle is not found.
        DriverFixture other = approvedDriver(admin, Map.of());
        apply(driver, id, other.vehicleId()).andExpect(status().isNotFound());

        // An order without an accepted price never reaches the marketplace.
        String unpriced = openShipment(customer, false);
        assertThat(marketplaceIds(other.session(), "")).contains(id).doesNotContain(unpriced);
        send(get("/api/v1/driver/marketplace/shipments/" + unpriced), other.session(), null).andExpect(status().isNotFound());
        apply(other, unpriced, other.vehicleId()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("SHIPMENT_NOT_AVAILABLE"));
    }

    @Test void withdrawRejectAndOwnership() throws Exception {
        Session customer = customer();
        String id = openShipment(customer, true);
        DriverFixture driver = approvedDriver(admin, Map.of());

        String first = applicationId(driver, id);
        send(post("/api/v1/driver/applications/" + first + "/withdraw"), driver.session(), Map.of("reason", "Bận việc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        assertThat(notificationTypes(customer)).contains("APPLICATION_WITHDRAWN");
        String second = applicationId(driver, id);   // re-applying after withdrawing is allowed

        // Another customer and another driver see nothing; drivers cannot use customer endpoints.
        Session stranger = customer();
        send(get("/api/v1/customer/shipments/" + id + "/applications"), stranger, null).andExpect(status().isNotFound());
        send(post("/api/v1/customer/shipments/" + id + "/applications/" + second + "/accept"), stranger, null).andExpect(status().isNotFound());
        DriverFixture otherDriver = approvedDriver(admin, Map.of());
        send(get("/api/v1/driver/applications/" + second), otherDriver.session(), null).andExpect(status().isNotFound());
        send(post("/api/v1/driver/applications/" + second + "/withdraw"), otherDriver.session(), Map.of()).andExpect(status().isNotFound());
        send(get("/api/v1/customer/shipments/" + id + "/applications"), driver.session(), null).andExpect(status().isForbidden());
        send(get("/api/v1/driver/marketplace/shipments"), customer, null).andExpect(status().isForbidden());

        send(post("/api/v1/customer/shipments/" + id + "/applications/" + second + "/reject"), customer, Map.of("reason", "Cần xe đến sớm hơn"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"));
        apply(driver, id, driver.vehicleId()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("APPLICATION_REJECTED_BEFORE"));
        send(post("/api/v1/customer/shipments/" + id + "/applications/" + second + "/accept"), customer, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("APPLICATION_NOT_PENDING"));

        JsonNode mine = data(send(get("/api/v1/driver/applications/my?status=REJECTED"), driver.session(), null).andExpect(status().isOk()));
        assertThat(mine.get("items")).hasSize(1);
        assertThat(mine.at("/items/0/id").asText()).isEqualTo(second);
        assertThat(mine.at("/items/0/responseReason").asText()).isEqualTo("Cần xe đến sớm hơn");
    }

    @Test void cancellingOrExpiringAnOrderClosesItsApplications() throws Exception {
        Session customer = customer();
        String cancelled = openShipment(customer, true);
        String expiring = openShipment(customer, true);
        DriverFixture driver = approvedDriver(admin, Map.of());
        String app1 = applicationId(driver, cancelled);
        String app2 = applicationId(driver, expiring);

        send(post("/api/v1/shipments/" + cancelled + "/cancel"), customer, Map.of("reason", "Không cần chở nữa")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from driver_shipment_applications where id = ?::uuid", String.class, app1)).isEqualTo("EXPIRED");

        jdbc.update("update shipments set published_at = now() - interval '2 minutes', pickup_time = now() - interval '1 minute' where id = ?::uuid", expiring);
        assertThat(lifecycle.expireIfDue(UUID.fromString(expiring))).isTrue();
        assertThat(lifecycle.expireIfDue(UUID.fromString(expiring))).isFalse();   // idempotent
        assertThat(jdbc.queryForObject("select status from shipments where id = ?::uuid", String.class, expiring)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select status from driver_shipment_applications where id = ?::uuid", String.class, app2)).isEqualTo("EXPIRED");
        assertThat(notificationTypes(driver.session())).containsExactlyInAnyOrder("APPLICATION_EXPIRED", "APPLICATION_EXPIRED");
        assertThat(notificationTypes(customer)).contains("SHIPMENT_EXPIRED");
    }

    @Test void notificationsBelongToTheirRecipient() throws Exception {
        Session customer = customer();
        String id = openShipment(customer, true);
        applicationId(approvedDriver(admin, Map.of()), id);
        applicationId(approvedDriver(admin, Map.of()), id);

        send(get("/api/v1/notifications/unread-count"), customer, null).andExpect(jsonPath("$.data.count").value(2));
        String first = data(send(get("/api/v1/notifications"), customer, null)).at("/items/0/id").asText();
        Session stranger = customer();
        send(patch("/api/v1/notifications/" + first + "/read"), stranger, null).andExpect(status().isNotFound());
        send(patch("/api/v1/notifications/" + first + "/read"), customer, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.read").value(true));
        send(get("/api/v1/notifications?unreadOnly=true"), customer, null).andExpect(jsonPath("$.data.totalElements").value(1));
        send(patch("/api/v1/notifications/read-all"), customer, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.updated").value(1));
        send(get("/api/v1/notifications/unread-count"), customer, null).andExpect(jsonPath("$.data.count").value(0));
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }

    @Test void adminMonitorsTheMarketplace() throws Exception {
        Session customer = customer();
        String id = openShipment(customer, true);
        DriverFixture driver = approvedDriver(admin, Map.of());
        String app = applicationId(driver, id);
        send(post("/api/v1/customer/shipments/" + id + "/applications/" + app + "/accept"), customer, null).andExpect(status().isOk());

        JsonNode shipments = data(send(get("/api/v1/admin/marketplace/shipments?status=ASSIGNED&size=100"), admin, null).andExpect(status().isOk()));
        JsonNode row = null;
        for (JsonNode s : shipments.get("items")) if (s.get("id").asText().equals(id)) row = s;
        assertThat(row).isNotNull();
        assertThat(row.get("totalApplications").asLong()).isEqualTo(1);
        assertThat(row.get("assignedDriverName").asText()).isEqualTo("Nguyen Van B");

        JsonNode apps = data(send(get("/api/v1/admin/marketplace/applications?shipmentId=" + id), admin, null)).get("items");
        assertThat(apps).hasSize(1);
        assertThat(apps.at("/0/id").asText()).isEqualTo(app);
        send(get("/api/v1/admin/marketplace/applications/" + app), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        assertThat(data(send(get("/api/v1/admin/marketplace/assignments?size=100"), admin, null)).get("items").findValuesAsText("applicationId")).contains(app);
        send(get("/api/v1/admin/marketplace/applications"), customer, null).andExpect(status().isForbidden());
    }

    @Test void marketplaceCacheIsDroppedWhenAnOrderIsAssigned() throws Exception {
        Session customer = customer();
        String id = openShipment(customer, true);
        DriverFixture driver = approvedDriver(admin, Map.of());
        assertThat(marketplaceIds(driver.session(), "")).contains(id);          // fills the cache
        String generation = redisTemplate.opsForValue().get("marketplace:v1:generation");
        String app = applicationId(driver, id);
        send(post("/api/v1/customer/shipments/" + id + "/applications/" + app + "/accept"), customer, null).andExpect(status().isOk());
        assertThat(redisTemplate.opsForValue().get("marketplace:v1:generation")).isNotEqualTo(generation);
        assertThat(marketplaceIds(driver.session(), "")).doesNotContain(id);
    }
}
