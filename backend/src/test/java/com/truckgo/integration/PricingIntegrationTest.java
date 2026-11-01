package com.truckgo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.truckgo.pricing.quote.QuoteService;
import com.truckgo.pricing.route.RouteDistanceResult;
import com.truckgo.pricing.route.RouteDistanceService;
import com.truckgo.shipment.ShipmentStop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@Import(PricingIntegrationTest.RouteStub.class)
class PricingIntegrationTest extends IntegrationTestSupport {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** Stands in for the map provider so tests control the road distance (or its absence). */
    @TestConfiguration
    static class RouteStub {
        static volatile RouteDistanceResult next = RouteDistanceResult.of(new BigDecimal("20.00"), 35, "stub", null);

        @Bean @Primary
        RouteDistanceService stubRoutes() { return (List<ShipmentStop> stops) -> next; }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired QuoteService quoteService;

    Session admin;
    String vehicle;

    @BeforeEach void setUp() throws Exception {
        RouteStub.next = RouteDistanceResult.of(new BigDecimal("20.00"), 35, "stub", null);
        admin = newAdmin();
        // Each test prices its own vehicle type (all big enough for the 600 kg test cargo) so tables rarely collide;
        // any leftover table of that type is switched off first.
        vehicle = List.of("TRUCK_1T", "TRUCK_1_5T", "TRUCK_2_5T", "TRUCK_3_5T", "TRUCK_5T", "TRUCK_8T", "TRUCK_10T", "TRUCK_15T", "TRUCK_OVER_15T")
                .get(counter++ % 9);
        jdbc.update("update pricing_configs set is_active = false where vehicle_type_code = ?", vehicle);
        redisTemplate.delete("pricing:v1:active-configs");
    }
    static int counter = 0;

    // ------------------------------------------------------------------ helpers

    ResultActions send(MockHttpServletRequestBuilder builder, Session session, Object body) throws Exception {
        builder.header("Authorization", bearer(session));
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(builder);
    }

    JsonNode data(ResultActions actions) throws Exception { return body(actions.andReturn()).get("data"); }

    /** The spec example: base 100,000; 8,000/km; 500 kg included then 500/kg; extra stop 20,000; loading 30,000. */
    Map<String, Object> config(String base) {
        Map<String, Object> c = new HashMap<>();
        c.put("name", "Bảng giá " + vehicle);
        c.put("vehicleTypeCode", vehicle);
        c.put("basePrice", base); c.put("pricePerKm", "8000"); c.put("includedDistanceKm", "0");
        c.put("includedWeightKg", "500"); c.put("pricePerExtraKg", "500");
        c.put("includedVolumeM3", "5"); c.put("pricePerExtraM3", "40000");
        c.put("additionalStopPrice", "20000"); c.put("minimumPrice", "0");
        c.put("effectiveFrom", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString());
        return c;
    }

    JsonNode createConfig(Map<String, Object> config) throws Exception {
        JsonNode created = data(send(post("/api/v1/admin/pricing-configs"), admin, config).andExpect(status().isCreated()));
        send(post("/api/v1/admin/pricing-service-fees"), admin, Map.of("pricingConfigId", created.get("id").asText(), "serviceCode", "LOADING_SUPPORT",
                "pricingType", "FIXED", "amount", "30000")).andExpect(status().isCreated());
        return created;
    }

    static Map<String, Object> stop(String type, int seq, String name, String phone) {
        return new HashMap<>(Map.of("stopType", type, "sequenceNo", seq, "contactName", name, "contactPhone", phone, "province", "Hà Nội", "addressDetail", "Số " + seq));
    }

    Map<String, Object> draftBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("pickupTime", OffsetDateTime.now(ZoneOffset.ofHours(7)).plusDays(2).withHour(10).truncatedTo(ChronoUnit.HOURS).toString());
        body.put("vehicleTypeCode", vehicle);
        body.put("stops", List.of(stop("PICKUP", 1, "Kho", "0912345678"), stop("DELIVERY", 2, "Khách 1", "0987654321"), stop("DELIVERY", 3, "Khách 2", "0987654322")));
        body.put("items", List.of(Map.of("itemName", "Máy giặt", "category", "ELECTRONICS", "quantity", 10, "weightPerUnitKg", "60", "lengthCm", "60", "widthCm", "60", "heightCm", "90")));
        body.put("services", List.of(Map.of("serviceCode", "LOADING_SUPPORT", "serviceValue", "TWO_PEOPLE")));
        return body;
    }

    Session customer() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        return login(email, PASSWORD);
    }

    JsonNode draft(Session customer, Map<String, Object> body) throws Exception {
        return data(send(post("/api/v1/shipments/draft"), customer, body).andExpect(status().isCreated()));
    }

    JsonNode preview(Session customer, String shipmentId) throws Exception {
        return data(send(post("/api/v1/shipments/" + shipmentId + "/pricing/preview"), customer, null).andExpect(status().isOk()));
    }

    // ------------------------------------------------------------------ tests

    @Test void pricesTheSpecExampleWithAConsistentBreakdown() throws Exception {
        createConfig(config("100000"));
        Session customer = customer();
        String id = draft(customer, draftBody()).get("id").asText();
        JsonNode q = preview(customer, id);
        assertThat(q.get("status").asText()).isEqualTo("CALCULATED");
        assertThat(q.get("quoteCode").asText()).endsWith("-Q1");
        assertThat(q.get("distanceKm").decimalValue()).isEqualByComparingTo("20");
        JsonNode b = q.get("breakdown");
        assertThat(b.get("basePrice").decimalValue()).isEqualByComparingTo("100000");
        assertThat(b.get("distancePrice").decimalValue()).isEqualByComparingTo("160000");
        assertThat(b.get("weightSurcharge").decimalValue()).isEqualByComparingTo("50000");      // 600 kg − 500 included
        assertThat(b.get("volumeSurcharge").decimalValue()).isEqualByComparingTo("0");          // 3.24 m³ < 5 included
        assertThat(b.get("serviceSurcharge").decimalValue()).isEqualByComparingTo("30000");
        assertThat(b.get("additionalStopSurcharge").decimalValue()).isEqualByComparingTo("20000");
        assertThat(q.get("totalPrice").decimalValue()).isEqualByComparingTo("360000");
        BigDecimal lines = BigDecimal.ZERO;
        for (JsonNode line : q.get("lines")) lines = lines.add(line.get("amount").decimalValue());
        assertThat(lines).isEqualByComparingTo(q.get("totalPrice").decimalValue());
        assertThat(jdbc.queryForObject("select sum(amount) from shipment_quote_items where quote_id = ?::uuid", BigDecimal.class, q.get("id").asText()))
                .isEqualByComparingTo("360000");
        assertThat(q.get("expiresAt").isNull()).isFalse();
        assertThat(q.at("/actions/acceptable").asBoolean()).isTrue();
        String snapshot = jdbc.queryForObject("select pricing_snapshot::text from shipment_quotes where id = ?::uuid", String.class, q.get("id").asText());
        assertThat(snapshot).contains("\"basePrice\"").contains("\"pricePerKm\"").contains("LOADING_SUPPORT");

        send(get("/api/v1/shipments/" + id), customer, null).andExpect(jsonPath("$.data.priceStatus").value("QUOTED"))
                .andExpect(jsonPath("$.data.estimatedPrice").value(360000));
    }

    @Test void volumeSurchargeAndAutoSelectedVehicle() throws Exception {
        Map<String, Object> cfg = config("100000");
        cfg.put("includedVolumeM3", "1");
        createConfig(cfg);
        Session customer = customer();
        Map<String, Object> body = draftBody();
        body.remove("vehicleTypeCode");
        body.put("items", List.of(Map.of("itemName", "Thùng nhẹ", "category", "OTHER", "quantity", 1, "weightPerUnitKg", "5", "lengthCm", "200", "widthCm", "100", "heightCm", "100")));
        String id = draft(customer, body).get("id").asText();
        JsonNode q = preview(customer, id);
        // The smallest priced type covering 5 kg is chosen among types with an effective table; only ours has one with is_active here.
        assertThat(q.get("status").asText()).isEqualTo("CALCULATED");
        // Smallest priced type covering 5 kg: tables of other tests may exist, so only check ours when it was picked.
        if (q.get("vehicleTypeCode").asText().equals(vehicle))
            assertThat(q.at("/breakdown/volumeSurcharge").decimalValue()).isEqualByComparingTo("40000"); // (2 − 1) m³ × 40,000
    }

    @Test void noMatchingOrExpiredPriceTableNeedsManualReview() throws Exception {
        Session customer = customer();
        String id = draft(customer, draftBody()).get("id").asText();
        JsonNode none = preview(customer, id);
        assertThat(none.get("status").asText()).isEqualTo("AWAITING_MANUAL_REVIEW");
        assertThat(none.get("reviewReasonCode").asText()).isEqualTo("NO_PRICING_CONFIG");
        assertThat(none.get("totalPrice").isNull()).isTrue();
        send(get("/api/v1/shipments/" + id), customer, null).andExpect(jsonPath("$.data.priceStatus").value("AWAITING_REVIEW"));

        JsonNode cfg = createConfig(config("100000"));
        jdbc.update("update pricing_configs set effective_from = now() - interval '10 days', effective_to = now() - interval '1 day' where id = ?::uuid", cfg.get("id").asText());
        redisTemplate.delete("pricing:v1:active-configs");
        JsonNode expired = preview(customer, id);
        assertThat(expired.get("reviewReasonCode").asText()).as("an expired table is never used").isEqualTo("NO_PRICING_CONFIG");
    }

    @Test void providerFailureWaitsForAdminWhoEntersAVerifiedDistance() throws Exception {
        createConfig(config("100000"));
        RouteStub.next = RouteDistanceResult.unavailable("stub", "PROVIDER_ERROR", "Không kết nối được dịch vụ bản đồ");
        Session customer = customer();
        String id = draft(customer, draftBody()).get("id").asText();
        JsonNode waiting = preview(customer, id);
        assertThat(waiting.get("status").asText()).isEqualTo("AWAITING_MANUAL_REVIEW");
        assertThat(waiting.get("distanceKm").isNull()).as("no invented distance").isTrue();
        assertThat(waiting.get("totalPrice").isNull()).isTrue();
        send(post("/api/v1/shipments/" + id + "/pricing/accept"), customer, null).andExpect(status().isConflict());

        JsonNode pending = data(send(get("/api/v1/admin/quotes/pending?size=100"), admin, null).andExpect(status().isOk()));
        assertThat(pending.get("items").findValuesAsText("quoteCode")).contains(waiting.get("quoteCode").asText());
        send(get("/api/v1/admin/quotes/pending"), customer, null).andExpect(status().isForbidden());

        String quoteId = waiting.get("id").asText();
        send(post("/api/v1/admin/quotes/" + quoteId + "/manual-adjustment"), admin, Map.of("reason", "Thiếu quãng đường")).andExpect(status().isBadRequest());
        send(post("/api/v1/admin/quotes/" + quoteId + "/manual-adjustment"), admin, Map.of("adjustmentAmount", "-10000", "reason", "Giảm giá"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.distanceKm").exists());
        send(post("/api/v1/admin/quotes/" + quoteId + "/manual-adjustment"), customer, Map.of("distanceKm", "25", "reason", "Tự sửa giá")).andExpect(status().isForbidden());
        JsonNode adjusted = data(send(post("/api/v1/admin/quotes/" + quoteId + "/manual-adjustment"), admin,
                Map.of("distanceKm", "25.5", "adjustmentAmount", "-14000", "reason", "Đã xác minh tuyến qua QL32")).andExpect(status().isOk())).get("quote");
        assertThat(adjusted.get("status").asText()).isEqualTo("CALCULATED");
        assertThat(adjusted.get("distanceSource").asText()).isEqualTo("MANUAL");
        assertThat(adjusted.get("quoteVersion").asInt()).isEqualTo(2);
        // 100,000 + 25.5×8,000 + 50,000 + 30,000 + 20,000 − 14,000
        assertThat(adjusted.get("totalPrice").decimalValue()).isEqualByComparingTo("390000");
        assertThat(adjusted.get("adjustmentReason").asText()).isEqualTo("Đã xác minh tuyến qua QL32");
        assertThat(jdbc.queryForObject("select status from shipment_quotes where id = ?::uuid", String.class, quoteId)).isEqualTo("SUPERSEDED");
        String adminId = admin.user().get("id").asText();
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_logs where action = 'QUOTE_MANUAL_ADJUSTMENT' and actor_id = ?::uuid and target_id = ?::uuid",
                Integer.class, adminId, adjusted.get("id").asText())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select adjusted_by::text from shipment_quotes where id = ?::uuid", String.class, adjusted.get("id").asText())).isEqualTo(adminId);

        // Recalculating keeps the verified distance while the provider is still down.
        JsonNode recalculated = data(send(post("/api/v1/admin/quotes/" + adjusted.get("id").asText() + "/recalculate"), admin, null).andExpect(status().isOk())).get("quote");
        assertThat(recalculated.get("distanceKm").decimalValue()).isEqualByComparingTo("25.5");
        assertThat(recalculated.get("quoteVersion").asInt()).isEqualTo(3);
        send(post("/api/v1/admin/quotes/" + adjusted.get("id").asText() + "/recalculate"), admin, null).andExpect(status().isConflict());
    }

    @Test void customersOnlySeeTheirOwnQuotes() throws Exception {
        createConfig(config("100000"));
        Session owner = customer();
        Session other = customer();
        String id = draft(owner, draftBody()).get("id").asText();
        preview(owner, id);
        for (var request : List.of(post("/api/v1/shipments/" + id + "/pricing/preview"), get("/api/v1/shipments/" + id + "/pricing"),
                get("/api/v1/shipments/" + id + "/pricing/history"), post("/api/v1/shipments/" + id + "/pricing/accept"), post("/api/v1/shipments/" + id + "/pricing/reject")))
            send(request, other, null).andExpect(status().isNotFound());
        String driverEmail = uniqueEmail();
        registerDriver(driverEmail);
        send(get("/api/v1/shipments/" + id + "/pricing"), login(driverEmail, PASSWORD), null).andExpect(status().isForbidden());
        send(get("/api/v1/shipments/" + id + "/pricing"), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.actions.acceptable").value(false));
        send(post("/api/v1/shipments/" + id + "/pricing/accept"), admin, null).andExpect(status().isForbidden());
        send(get("/api/v1/admin/pricing-configs"), owner, null).andExpect(status().isForbidden());
    }

    @Test void expiredQuoteCannotBeAccepted() throws Exception {
        createConfig(config("100000"));
        Session customer = customer();
        String id = draft(customer, draftBody()).get("id").asText();
        JsonNode q = preview(customer, id);
        jdbc.update("update shipment_quotes set expires_at = now() - interval '1 minute' where id = ?::uuid", q.get("id").asText());
        send(post("/api/v1/shipments/" + id + "/pricing/accept"), customer, null).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select status from shipment_quotes where id = ?::uuid", String.class, q.get("id").asText())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select estimated_price from shipments where id = ?::uuid", BigDecimal.class, id)).isNull();
        assertThat(preview(customer, id).get("quoteVersion").asInt()).as("a fresh quote can be requested").isEqualTo(2);
    }

    @Test void acceptedQuoteIsFrozenAndRejectWorks() throws Exception {
        createConfig(config("100000"));
        Session customer = customer();
        JsonNode created = draft(customer, draftBody());
        String id = created.get("id").asText();
        JsonNode q = preview(customer, id);
        JsonNode accepted = data(send(post("/api/v1/shipments/" + id + "/pricing/accept"), customer, null).andExpect(status().isOk()));
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("select estimated_price from shipments where id = ?::uuid", BigDecimal.class, id)).isEqualByComparingTo("360000");
        send(get("/api/v1/shipments/" + id), customer, null).andExpect(jsonPath("$.data.priceStatus").value("ACCEPTED"));

        send(post("/api/v1/shipments/" + id + "/pricing/preview"), customer, null).andExpect(status().isConflict());
        send(post("/api/v1/shipments/" + id + "/pricing/accept"), customer, null).andExpect(status().isConflict());
        send(post("/api/v1/shipments/" + id + "/pricing/reject"), customer, Map.of("reason", "Đổi ý")).andExpect(status().isConflict());
        send(post("/api/v1/admin/quotes/" + q.get("id").asText() + "/manual-adjustment"), admin, Map.of("distanceKm", "5", "reason", "Thử sửa giá đã chốt"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select total_price from shipment_quotes where id = ?::uuid", BigDecimal.class, q.get("id").asText())).isEqualByComparingTo("360000");

        // Editing the draft supersedes even an accepted quote; its amounts stay as they were.
        Map<String, Object> update = draftBody();
        update.put("version", data(send(get("/api/v1/shipments/" + id), customer, null)).get("version").asLong());
        send(put("/api/v1/shipments/" + id + "/draft"), customer, update).andExpect(status().isOk()).andExpect(jsonPath("$.data.priceStatus").value("NOT_QUOTED"));
        assertThat(jdbc.queryForObject("select status from shipment_quotes where id = ?::uuid", String.class, q.get("id").asText())).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("select total_price from shipment_quotes where id = ?::uuid", BigDecimal.class, q.get("id").asText())).isEqualByComparingTo("360000");
        assertThat(jdbc.queryForObject("select estimated_price from shipments where id = ?::uuid", BigDecimal.class, id)).isNull();

        preview(customer, id);
        JsonNode rejected = data(send(post("/api/v1/shipments/" + id + "/pricing/reject"), customer, Map.of("reason", "Giá cao")).andExpect(status().isOk()));
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejectionReason").asText()).isEqualTo("Giá cao");
        JsonNode history = data(send(get("/api/v1/shipments/" + id + "/pricing/history"), customer, null));
        assertThat(history.findValuesAsText("status")).containsExactly("REJECTED", "SUPERSEDED");
    }

    @Test void concurrentIdenticalPreviewsCreateOneVersionAndOneCurrentQuote() throws Exception {
        createConfig(config("100000"));
        Session customer = customer();
        String id = draft(customer, draftBody()).get("id").asText();
        UUID customerId = UUID.fromString(customer.user().get("id").asText());
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Object> task = () -> { start.await(); return quoteService.preview(customerId, UUID.fromString(id)); };
            results.add(pool.submit(task));
        }
        start.countDown();
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (Future<Object> r : results) codes.add(((com.truckgo.pricing.dto.QuoteDtos.QuoteResponse) r.get()).quoteCode());
        pool.shutdown();
        // The order lock serialises them; the first prices the order, the others get that same still-valid quote.
        assertThat(jdbc.queryForList("select quote_version from shipment_quotes where shipment_id = ?::uuid order by quote_version", Integer.class, id))
                .containsExactly(1);
        assertThat(codes).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from shipment_quotes where shipment_id = ?::uuid and status in ('PENDING','CALCULATED','AWAITING_MANUAL_REVIEW','ACCEPTED')",
                Integer.class, id)).isEqualTo(1);
    }

    @Test void changingThePriceTableNeverAltersExistingQuotes() throws Exception {
        JsonNode cfg = createConfig(config("100000"));
        Session customer = customer();
        String id = draft(customer, draftBody()).get("id").asText();
        JsonNode first = preview(customer, id);
        Map<String, Object> update = config("150000");
        update.put("version", data(send(get("/api/v1/admin/pricing-configs/" + cfg.get("id").asText()), admin, null)).get("version").asLong());
        send(put("/api/v1/admin/pricing-configs/" + cfg.get("id").asText()), admin, update).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select total_price from shipment_quotes where id = ?::uuid", BigDecimal.class, first.get("id").asText())).isEqualByComparingTo("360000");
        assertThat(jdbc.queryForObject("select base_price from shipment_quotes where id = ?::uuid", BigDecimal.class, first.get("id").asText())).isEqualByComparingTo("100000");
        JsonNode second = preview(customer, id);
        assertThat(second.get("totalPrice").decimalValue()).isEqualByComparingTo("410000");
        JsonNode history = data(send(get("/api/v1/admin/pricing-configs/" + cfg.get("id").asText() + "/history"), admin, null));
        assertThat(history.findValuesAsText("action")).contains("CREATED", "SERVICE_FEE_CREATED", "UPDATED");
        assertThat(history.get(0).get("oldValue").asText()).contains("100000");
        assertThat(history.get(0).get("newValue").asText()).contains("150000");
    }

    @Test void priceTableValidationOverlapAndCache() throws Exception {
        Map<String, Object> negative = config("-1");
        send(post("/api/v1/admin/pricing-configs"), admin, negative).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.basePrice").exists());
        Map<String, Object> inverted = config("100000");
        inverted.put("effectiveTo", OffsetDateTime.now(ZoneOffset.UTC).minusDays(2).toString());
        send(post("/api/v1/admin/pricing-configs"), admin, inverted).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.effectiveTo").exists());
        Map<String, Object> badRule = config("100000");
        badRule.put("timeRules", List.of(Map.of("name", "Đêm", "startTime", "22:00", "endTime", "22:00", "feeType", "PERCENTAGE", "percentage", "10")));
        send(post("/api/v1/admin/pricing-configs"), admin, badRule).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data['timeRules[0].endTime']").exists());

        JsonNode cfg = createConfig(config("100000"));
        send(post("/api/v1/admin/pricing-configs"), admin, config("120000")).andExpect(status().isConflict()).andExpect(jsonPath("$.data.effectiveFrom").exists());
        assertThat(jdbc.queryForObject("select count(*) from pricing_configs where vehicle_type_code = ? and is_active", Integer.class, vehicle)).isEqualTo(1);

        String configId = cfg.get("id").asText();
        send(post("/api/v1/admin/pricing-service-fees"), admin, Map.of("pricingConfigId", configId, "serviceCode", "LOADING_SUPPORT", "pricingType", "FIXED", "amount", "1"))
                .andExpect(status().isConflict());
        send(post("/api/v1/admin/pricing-service-fees"), admin, Map.of("pricingConfigId", configId, "serviceCode", "FREE_PIZZA", "pricingType", "FIXED", "amount", "1"))
                .andExpect(status().isBadRequest());
        send(post("/api/v1/admin/pricing-service-fees"), admin, Map.of("pricingConfigId", configId, "serviceCode", "FRAGILE_GOODS", "serviceOptionValue", "TEAM",
                "pricingType", "FIXED", "amount", "1")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.serviceOptionValue").exists());
        send(post("/api/v1/admin/pricing-service-fees"), admin, Map.of("pricingConfigId", configId, "serviceCode", "FRAGILE_GOODS", "pricingType", "PERCENTAGE", "amount", "5"))
                .andExpect(status().isBadRequest());
        JsonNode team = data(send(post("/api/v1/admin/pricing-service-fees"), admin, Map.of("pricingConfigId", configId, "serviceCode", "LOADING_SUPPORT",
                "serviceOptionValue", "TEAM", "pricingType", "FIXED", "amount", "200000")).andExpect(status().isCreated()));
        send(delete("/api/v1/admin/pricing-service-fees/" + team.get("id").asText()), admin, null).andExpect(status().isOk());
        send(get("/api/v1/admin/pricing-service-fees?pricingConfigId=" + configId), admin, null).andExpect(jsonPath("$.data.length()").value(1));
        assertThat(jdbc.queryForObject("select count(*) from pricing_service_fees where id = ?::uuid", Integer.class, team.get("id").asText())).as("soft delete").isEqualTo(1);

        // The active tables are cached for quoting and evicted when an admin changes them.
        Session customer = customer();
        preview(customer, draft(customer, draftBody()).get("id").asText());
        assertThat(redisTemplate.hasKey("pricing:v1:active-configs")).isTrue();
        send(patch("/api/v1/admin/pricing-configs/" + configId + "/deactivate"), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        assertThat(redisTemplate.hasKey("pricing:v1:active-configs")).isFalse();
        send(patch("/api/v1/admin/pricing-configs/" + configId + "/deactivate"), admin, null).andExpect(status().isConflict());
        send(patch("/api/v1/admin/pricing-configs/" + configId + "/activate"), admin, null).andExpect(status().isOk());
        send(get("/api/v1/admin/pricing-configs?vehicleTypeCode=" + vehicle.toLowerCase()), admin, null).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test void quotingNeedsCompleteOrderData() throws Exception {
        createConfig(config("100000"));
        Session customer = customer();
        Map<String, Object> body = draftBody();
        body.put("items", List.of(Map.of("itemName", "Bao gạo", "category", "AGRICULTURAL", "quantity", 5, "weightPerUnitKg", "50")));
        String id = draft(customer, body).get("id").asText();
        send(post("/api/v1/shipments/" + id + "/pricing/preview"), customer, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.items").exists());
        assertThat(jdbc.queryForObject("select count(*) from shipment_quotes where shipment_id = ?::uuid", Integer.class, id)).isZero();
        send(get("/api/v1/shipments/" + id + "/pricing"), customer, null).andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
    }
}
