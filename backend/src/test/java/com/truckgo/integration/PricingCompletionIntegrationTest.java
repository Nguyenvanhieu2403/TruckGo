package com.truckgo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.truckgo.pricing.quote.QuoteExpiryJob;
import com.truckgo.pricing.route.RouteDistanceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sprint 4 completion: expiry job, re-pricing limits and reuse, per-stop services, marketplace price check. */
@Testcontainers(disabledWithoutDocker = true)
@Import(PricingIntegrationTest.RouteStub.class)
class PricingCompletionIntegrationTest extends IntegrationTestSupport {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void limits(DynamicPropertyRegistry registry) {
        registry.add("app.pricing.preview-limit.per-shipment", () -> "2");
        registry.add("app.pricing.expiry-job-enabled", () -> "true");
        registry.add("app.pricing.expiry-interval", () -> "1h");   // tests run the job themselves
    }

    static final String VEHICLE = "TRUCK_2_5T";

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired QuoteExpiryJob expiryJob;

    Session admin;

    @BeforeEach void setUp() throws Exception {
        PricingIntegrationTest.RouteStub.next = RouteDistanceResult.of(new BigDecimal("20.00"), 35, "stub", null);
        admin = newAdmin();
        if (jdbc.queryForObject("select count(*) from pricing_configs where vehicle_type_code = ? and is_active", Long.class, VEHICLE) == 0) {
            Map<String, Object> c = new HashMap<>();
            c.put("name", "Bảng giá hoàn thiện"); c.put("vehicleTypeCode", VEHICLE);
            c.put("basePrice", "100000"); c.put("pricePerKm", "8000"); c.put("includedDistanceKm", "0");
            c.put("includedWeightKg", "2500"); c.put("pricePerExtraKg", "0"); c.put("includedVolumeM3", "20"); c.put("pricePerExtraM3", "0");
            c.put("additionalStopPrice", "20000"); c.put("minimumPrice", "0");
            c.put("effectiveFrom", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString());
            String configId = data(send(post("/api/v1/admin/pricing-configs"), admin, c).andExpect(status().isCreated())).get("id").asText();
            for (Map<String, Object> fee : List.of(
                    Map.<String, Object>of("serviceCode", "LOADING_SUPPORT", "serviceOptionValue", "TWO_PEOPLE", "pricingType", "FIXED", "amount", "110000"),
                    Map.<String, Object>of("serviceCode", "UNLOADING_SUPPORT", "serviceOptionValue", "TWO_PEOPLE", "pricingType", "FIXED", "amount", "90000"))) {
                Map<String, Object> body = new HashMap<>(fee);
                body.put("pricingConfigId", configId);
                body.put("percentage", "0");
                send(post("/api/v1/admin/pricing-service-fees"), admin, body).andExpect(status().isCreated());
            }
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

    static Map<String, Object> stop(String type, int seq, List<Map<String, Object>> services) {
        Map<String, Object> s = new HashMap<>(Map.of("stopType", type, "sequenceNo", seq, "contactName", "Liên hệ " + seq, "contactPhone", "091234567" + seq,
                "province", "Hà Nội", "addressDetail", "Số " + seq));
        if (services != null) s.put("services", services);
        return s;
    }

    static Map<String, Object> body(List<Map<String, Object>> stops, List<Map<String, Object>> services, int quantity) {
        Map<String, Object> b = new HashMap<>();
        b.put("pickupTime", OffsetDateTime.now(ZoneOffset.ofHours(7)).plusDays(2).withHour(10).truncatedTo(ChronoUnit.HOURS).toString());
        b.put("vehicleTypeCode", VEHICLE);
        b.put("stops", stops);
        b.put("items", List.of(Map.of("itemName", "Máy giặt", "category", "ELECTRONICS", "quantity", quantity, "weightPerUnitKg", "60",
                "lengthCm", "60", "widthCm", "60", "heightCm", "90")));
        b.put("services", services);
        return b;
    }

    static Map<String, Object> simple(int quantity) {
        return body(List.of(stop("PICKUP", 1, null), stop("DELIVERY", 2, null)), List.of(), quantity);
    }

    JsonNode draft(Session customer, Map<String, Object> body) throws Exception {
        return data(send(post("/api/v1/shipments/draft"), customer, body).andExpect(status().isCreated()));
    }

    ResultActions preview(Session customer, String id) throws Exception { return send(post("/api/v1/shipments/" + id + "/pricing/preview"), customer, null); }

    String priced(Session customer) throws Exception {
        String id = draft(customer, simple(10)).get("id").asText();
        preview(customer, id).andExpect(status().isOk());
        return id;
    }

    String quoteField(String shipmentId, String column) {
        return jdbc.queryForObject("select " + column + "::text from shipment_quotes where shipment_id = ?::uuid order by quote_version desc limit 1", String.class, shipmentId);
    }

    // ------------------------------------------------------------------ expiry job

    @Test void expiryJobExpiresOnlyDueCalculatedQuotesOnceAcrossInstances() throws Exception {
        Session customer = customer();
        String due = priced(customer), fresh = priced(customer), accepted = priced(customer), rejected = priced(customer);
        send(post("/api/v1/shipments/" + accepted + "/pricing/accept"), customer, null).andExpect(status().isOk());
        send(post("/api/v1/shipments/" + rejected + "/pricing/reject"), customer, Map.of("reason", "Đắt")).andExpect(status().isOk());
        for (String id : List.of(due, accepted, rejected))
            jdbc.update("update shipment_quotes set expires_at = now() - interval '1 minute' where shipment_id = ?::uuid", id);
        String snapshot = quoteField(due, "pricing_snapshot");
        String total = quoteField(due, "total_price");

        // Three "instances" run the job at the same moment.
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Callable<Integer> task = () -> { start.await(); return expiryJob.run(); };
            runs.add(pool.submit(task));
        }
        start.countDown();
        int expiredTotal = 0;
        for (Future<Integer> f : runs) expiredTotal += f.get();
        pool.shutdown();

        assertThat(quoteField(due, "status")).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from shipment_quotes where shipment_id = ?::uuid and status = 'EXPIRED'", Long.class, due)).isEqualTo(1);
        assertThat(quoteField(fresh, "status")).isEqualTo("CALCULATED");
        assertThat(quoteField(accepted, "status")).isEqualTo("ACCEPTED");
        assertThat(quoteField(rejected, "status")).isEqualTo("REJECTED");
        assertThat(quoteField(due, "pricing_snapshot")).isEqualTo(snapshot);   // amounts and snapshot untouched
        assertThat(quoteField(due, "total_price")).isEqualTo(total);
        assertThat(expiredTotal).isGreaterThanOrEqualTo(1);
        assertThat(expiryJob.run()).isZero();
    }

    @Test void readingADueQuoteReportsExpiredEvenBeforeTheJob() throws Exception {
        Session customer = customer();
        String id = priced(customer);
        jdbc.update("update shipment_quotes set expires_at = now() - interval '1 minute' where shipment_id = ?::uuid", id);
        send(get("/api/v1/shipments/" + id + "/pricing"), customer, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXPIRED"));
    }

    // ------------------------------------------------------------------ re-pricing

    @Test void unchangedOrdersReuseTheQuoteAndRepricingIsRateLimited() throws Exception {
        Session customer = customer();
        String id = draft(customer, simple(10)).get("id").asText();
        String first = data(preview(customer, id).andExpect(status().isOk())).get("quoteCode").asText();
        // Pressing again without changes: same quote, no new version, never limited.
        for (int i = 0; i < 4; i++) assertThat(data(preview(customer, id).andExpect(status().isOk())).get("quoteCode").asText()).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from shipment_quotes where shipment_id = ?::uuid", Long.class, id)).isEqualTo(1);

        // A real change gives a new version; the third new version within the window is refused (limit 2 in this test).
        JsonNode current = data(send(get("/api/v1/shipments/" + id), customer, null));
        Map<String, Object> changed = simple(12);
        changed.put("version", current.get("version").asLong());
        send(put("/api/v1/shipments/" + id + "/draft"), customer, changed).andExpect(status().isOk());
        String second = data(preview(customer, id).andExpect(status().isOk())).get("quoteCode").asText();
        assertThat(second).isNotEqualTo(first).endsWith("-Q2");

        current = data(send(get("/api/v1/shipments/" + id), customer, null));
        Map<String, Object> again = simple(14);
        again.put("version", current.get("version").asLong());
        send(put("/api/v1/shipments/" + id + "/draft"), customer, again).andExpect(status().isOk());
        preview(customer, id).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("thử lại sau")));
        assertThat(jdbc.queryForObject("select count(*) from shipment_quotes where shipment_id = ?::uuid", Long.class, id)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ per-stop services

    @Test void loadingAndUnloadingArePricedPerStopWithoutDoubleCharging() throws Exception {
        Session customer = customer();
        Map<String, Object> loading = Map.of("serviceCode", "LOADING_SUPPORT", "serviceValue", "TWO_PEOPLE");
        Map<String, Object> unloading = Map.of("serviceCode", "UNLOADING_SUPPORT", "serviceValue", "TWO_PEOPLE");
        JsonNode created = draft(customer, body(List.of(stop("PICKUP", 1, List.of(loading)), stop("DELIVERY", 2, List.of(unloading)),
                stop("DELIVERY", 3, List.of(unloading))), List.of(), 10));
        assertThat(created.at("/stops/0/services/0/serviceCode").asText()).isEqualTo("LOADING_SUPPORT");
        String id = created.get("id").asText();
        JsonNode quote = data(preview(customer, id).andExpect(status().isOk()));
        JsonNode lines = quote.get("lines");
        JsonNode load = null, unload = null;
        for (JsonNode l : lines) {
            if (l.get("code").asText().equals("STOP_SERVICE:LOADING_SUPPORT:TWO_PEOPLE")) load = l;
            if (l.get("code").asText().equals("STOP_SERVICE:UNLOADING_SUPPORT:TWO_PEOPLE")) unload = l;
        }
        assertThat(load).isNotNull();
        assertThat(load.get("appliesTo").asText()).isEqualTo("STOP");
        assertThat(load.get("stopSequences")).hasSize(1);
        assertThat(load.get("amount").decimalValue()).isEqualByComparingTo("110000");
        assertThat(unload.get("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(unload.get("amount").decimalValue()).isEqualByComparingTo("180000");   // 2 deliveries × 90,000, each once
        assertThat(unload.get("stopSequences").toString()).isEqualTo("[2,3]");
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode l : lines) sum = sum.add(l.get("amount").decimalValue());
        assertThat(sum).isEqualByComparingTo(quote.get("totalPrice").decimalValue());
        assertThat(quoteField(id, "pricing_snapshot")).contains("stopServices").contains("2:DELIVERY:UNLOADING_SUPPORT=TWO_PEOPLE");
        // Lines of older quotes keep ORDER scope (column default) and stay readable.
        assertThat(jdbc.queryForObject("select count(*) from shipment_quote_items where applies_to = 'STOP'", Long.class)).isGreaterThanOrEqualTo(2);
    }

    @Test void stopServicesAreValidatedAndCannotDuplicateOrderServices() throws Exception {
        Session customer = customer();
        Map<String, Object> unloading = Map.of("serviceCode", "UNLOADING_SUPPORT", "serviceValue", "TWO_PEOPLE");
        send(post("/api/v1/shipments/draft"), customer, body(List.of(stop("PICKUP", 1, List.of(unloading)), stop("DELIVERY", 2, null)), List.of(), 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data['stops[0].services[0].serviceCode']").exists());
        send(post("/api/v1/shipments/draft"), customer, body(List.of(stop("PICKUP", 1, null), stop("DELIVERY", 2, List.of(unloading))),
                List.of(unloading), 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.services").value(org.hamcrest.Matchers.containsString("không chọn cả hai")));
        send(post("/api/v1/shipments/draft"), customer, body(List.of(stop("PICKUP", 1, null), stop("DELIVERY", 2,
                List.of(Map.of("serviceCode", "UNLOADING_SUPPORT", "serviceValue", "THREE_PEOPLE")))), List.of(), 1)).andExpect(status().isBadRequest());
        // The old order-wide way still works.
        draft(customer, body(List.of(stop("PICKUP", 1, null), stop("DELIVERY", 2, null)), List.of(unloading), 1));
    }

    // ------------------------------------------------------------------ marketplace

    @Test void aDriverCannotBeChosenWhenTheAcceptedPriceIsGone() throws Exception {
        Session customer = customer();
        String id = priced(customer);
        send(post("/api/v1/shipments/" + id + "/pricing/accept"), customer, null).andExpect(status().isOk());
        send(post("/api/v1/shipments/" + id + "/publish"), customer, null).andExpect(status().isOk());
        DriverFixture driver = approvedDriver(admin, Map.of("vehicleType", "MEDIUM_TRUCK", "maxPayloadKg", 3000, "lengthCm", 500, "widthCm", 210, "heightCm", 210));
        String app = data(send(post("/api/v1/driver/marketplace/shipments/" + id + "/applications"), driver.session(), Map.of("vehicleId", driver.vehicleId()))
                .andExpect(status().isCreated())).get("id").asText();
        // Simulate data drift: the accepted quote is no longer accepted.
        jdbc.update("update shipment_quotes set status = 'SUPERSEDED', superseded_at = now() where shipment_id = ?::uuid and status = 'ACCEPTED'", id);
        send(post("/api/v1/customer/shipments/" + id + "/applications/" + app + "/accept"), customer, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("SHIPMENT_NOT_AVAILABLE"));
        assertThat(jdbc.queryForObject("select status from shipments where id = ?::uuid", String.class, id)).isEqualTo("PUBLISHED");
        assertThat(data(send(get("/api/v1/driver/marketplace/shipments?size=50"), driver.session(), null)).toString()).doesNotContain(id);
    }
}
