package com.truckgo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@Import(PricingIntegrationTest.RouteStub.class)
class TripIntegrationTest extends IntegrationTestSupport {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container
    static MinIOContainer minio = new MinIOContainer(DockerImageName.parse(ShipmentIntegrationTest.MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"));

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.provider", () -> "minio");
        registry.add("app.storage.endpoint", minio::getS3URL);
        registry.add("app.storage.access-key", minio::getUserName);
        registry.add("app.storage.secret-key", minio::getPassword);
        registry.add("app.storage.bucket", () -> "truckgo-trip-test");
    }

    static final String VEHICLE_TYPE = "TRUCK_1T";
    static final Pattern OTP = Pattern.compile("là (\\d{6})");

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;

    Session admin;

    /** One accepted, assigned order with its customer and driver. */
    record Trip(String id, Session customer, DriverFixture driver) { }

    @BeforeEach void setUp() throws Exception {
        admin = newAdmin();
        if (jdbc.queryForObject("select count(*) from pricing_configs where vehicle_type_code = ? and is_active", Long.class, VEHICLE_TYPE) == 0) {
            Map<String, Object> c = new HashMap<>();
            c.put("name", "Bảng giá chuyến"); c.put("vehicleTypeCode", VEHICLE_TYPE);
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

    static Map<String, Object> stop(String type, int seq, String province) {
        return new HashMap<>(Map.of("stopType", type, "sequenceNo", seq, "contactName", "Liên hệ " + seq, "contactPhone", "091234567" + seq,
                "province", province, "addressDetail", "Số " + seq + " đường Thử"));
    }

    /** Publishes an order with an accepted quote, lets one driver apply and has the customer choose them. */
    Trip assignedTrip(int deliveries) throws Exception {
        Session customer = customer();
        List<Map<String, Object>> stops = new ArrayList<>(List.of(stop("PICKUP", 1, "Hà Nội")));
        for (int i = 0; i < deliveries; i++) stops.add(stop("DELIVERY", i + 2, "Hải Phòng"));
        Map<String, Object> body = new HashMap<>();
        body.put("pickupTime", OffsetDateTime.now(ZoneOffset.ofHours(7)).plusDays(2).truncatedTo(ChronoUnit.HOURS).toString());
        body.put("vehicleTypeCode", VEHICLE_TYPE);
        body.put("stops", stops);
        body.put("items", List.of(Map.of("itemName", "Máy giặt", "category", "ELECTRONICS", "quantity", 10, "weightPerUnitKg", "60",
                "lengthCm", "60", "widthCm", "60", "heightCm", "90")));
        String id = data(send(post("/api/v1/shipments/draft"), customer, body).andExpect(status().isCreated())).get("id").asText();
        send(post("/api/v1/shipments/" + id + "/pricing/preview"), customer, null).andExpect(status().isOk());
        send(post("/api/v1/shipments/" + id + "/pricing/accept"), customer, null).andExpect(status().isOk());
        send(post("/api/v1/shipments/" + id + "/publish"), customer, null).andExpect(status().isOk());
        DriverFixture driver = approvedDriver(admin, Map.of());
        String app = data(send(post("/api/v1/driver/marketplace/shipments/" + id + "/applications"), driver.session(), Map.of("vehicleId", driver.vehicleId()))
                .andExpect(status().isCreated())).get("id").asText();
        send(post("/api/v1/customer/shipments/" + id + "/applications/" + app + "/accept"), customer, null).andExpect(status().isOk());
        return new Trip(id, customer, driver);
    }

    ResultActions driverAction(Trip trip, String action, Object body) throws Exception {
        return send(post("/api/v1/driver/trips/" + trip.id() + "/" + action), trip.driver().session(), body);
    }

    ResultActions location(Trip trip, double lat, double lng, OffsetDateTime at) throws Exception {
        return send(post("/api/v1/driver/trips/" + trip.id() + "/location"), trip.driver().session(),
                Map.of("latitude", lat, "longitude", lng, "accuracy", 8.5, "speed", 35.2, "heading", 120, "recordedAt", at.toString()));
    }

    void clearRateLimit(Trip trip) { redisTemplate.delete("truckgo:trip:" + trip.id() + ":gps-rate"); }

    String uploadPhoto(Trip trip, String purpose) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/v1/driver/trips/" + trip.id() + "/photos").file(new MockMultipartFile("file", "bang-chung.png", "image/png", ShipmentIntegrationTest.PNG))
                .param("purpose", purpose).header("Authorization", bearer(trip.driver().session()))).andExpect(status().isCreated()).andReturn();
        return body(result).at("/data/id").asText();
    }

    /** The code as delivered to the customer's in-app inbox (the only place it exists in plain text). */
    String latestOtp(Session customer) throws Exception {
        for (JsonNode n : data(send(get("/api/v1/notifications?size=50"), customer, null)).get("items")) {
            if (!n.get("type").asText().equals("DELIVERY_OTP")) continue;
            Matcher m = OTP.matcher(n.get("content").asText());
            if (m.find()) return m.group(1);
        }
        return null;
    }

    Trip delivered(int deliveries) throws Exception {
        Trip trip = assignedTrip(deliveries);
        driverAction(trip, "arrived", Map.of()).andExpect(status().isOk());
        driverAction(trip, "pickup", Map.of()).andExpect(status().isOk());
        driverAction(trip, "start", Map.of()).andExpect(status().isOk());
        driverAction(trip, "deliver", Map.of()).andExpect(status().isOk());
        return trip;
    }

    static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.ofHours(7)).truncatedTo(ChronoUnit.MILLIS); }

    // ------------------------------------------------------------------ tests

    @Test void completeTripFromAssignedToCompleted() throws Exception {
        Trip trip = assignedTrip(1);
        JsonNode list = data(send(get("/api/v1/driver/trips?view=UPCOMING"), trip.driver().session(), null).andExpect(status().isOk()));
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.at("/items/0/id").asText()).isEqualTo(trip.id());
        JsonNode detail = data(send(get("/api/v1/driver/trips/" + trip.id()), trip.driver().session(), null).andExpect(status().isOk()));
        assertThat(detail.get("nextAction").asText()).isEqualTo("ARRIVE_PICKUP");
        assertThat(detail.at("/stops/0/contactPhone").asText()).isNotEmpty();
        assertThat(detail.at("/quote/status").asText()).isEqualTo("ACCEPTED");
        assertThat(detail.get("trackingAllowed").asBoolean()).isFalse();

        // Out of order and GPS before transit are refused.
        driverAction(trip, "deliver", Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("INVALID_TRIP_STATUS"));
        driverAction(trip, "pickup", Map.of()).andExpect(status().isConflict());
        location(trip, 21.0285, 105.8542, now()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("TRACKING_NOT_ACTIVE"));

        driverAction(trip, "arrived", Map.of("latitude", 21.0285, "longitude", 105.8542, "note", "Đã đến điểm lấy hàng"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRIVER_ARRIVED")).andExpect(jsonPath("$.data.nextAction").value("CONFIRM_PICKUP"));
        String photo = uploadPhoto(trip, "PICKUP_PHOTO");
        driverAction(trip, "pickup", Map.of("note", "Đã nhận đủ hàng", "attachments", List.of(photo)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PICKED_UP"));
        driverAction(trip, "start", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.data.trackingAllowed").value(true));

        // No fix yet: offline, never a made-up position.
        JsonNode tracking = data(send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking"), trip.customer(), null).andExpect(status().isOk()));
        assertThat(tracking.get("state").asText()).isEqualTo("OFFLINE");
        assertThat(tracking.get("latitude").isNull()).isTrue();

        OffsetDateTime t1 = now();
        location(trip, 21.0285, 105.8542, t1).andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.policy.movingIntervalSeconds").isNumber());
        tracking = data(send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking"), trip.customer(), null));
        assertThat(tracking.get("state").asText()).isEqualTo("LIVE");
        assertThat(tracking.get("latitude").decimalValue()).isEqualByComparingTo("21.0285");
        assertThat(jdbc.queryForObject("select count(*) from shipment_tracking_locations where shipment_id = ?::uuid", Long.class, trip.id())).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get("truckgo:trip:" + trip.id() + ":latest-location")).contains("21.0285");

        // Rate limit, duplicates, impossible jumps, bad values and bad clocks.
        location(trip, 21.0286, 105.8543, t1.plusSeconds(1)).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.data.code").value("LOCATION_RATE_LIMITED"));
        location(trip, 21.0285, 105.8542, t1).andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(false)).andExpect(jsonPath("$.data.reason").value("DUPLICATE"));
        clearRateLimit(trip);
        location(trip, 10.7769, 106.7009, t1.plusSeconds(10)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.code").value("LOCATION_IMPLAUSIBLE"));
        location(trip, 95, 105.8542, t1.plusSeconds(10)).andExpect(status().isBadRequest());
        location(trip, 21.0285, 190, t1.plusSeconds(10)).andExpect(status().isBadRequest());
        location(trip, 21.03, 105.86, now().plusHours(1)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.code").value("LOCATION_INVALID_TIME"));
        clearRateLimit(trip);
        location(trip, 21.0300, 105.8560, t1.plusSeconds(10)).andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true));
        JsonNode history = data(send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking/history"), trip.customer(), null).andExpect(status().isOk()));
        assertThat(history.get("totalElements").asLong()).isEqualTo(2);

        String proof = uploadPhoto(trip, "DELIVERY_PHOTO");
        driverAction(trip, "deliver", Map.of("note", "Đã giao cho người nhận", "latitude", 21.0301, "longitude", 105.8561, "attachments", List.of(proof)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DELIVERED")).andExpect(jsonPath("$.data.awaitingCustomerConfirmation").value(true));
        clearRateLimit(trip);
        location(trip, 21.0302, 105.8562, now()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("TRACKING_NOT_ACTIVE"));
        assertThat(redisTemplate.hasKey("truckgo:trip:" + trip.id() + ":latest-location")).isFalse();

        // The code is never in an API response other than the customer's own inbox, and only its HMAC is stored.
        JsonNode view = data(send(get("/api/v1/customer/shipments/" + trip.id() + "/trip"), trip.customer(), null).andExpect(status().isOk()));
        assertThat(view.at("/confirmation/pending").asBoolean()).isTrue();
        assertThat(view.toString()).doesNotContainPattern("\"otp");
        assertThat(view.at("/tracking/state").asText()).isEqualTo("ENDED");
        assertThat(view.at("/tracking/latitude").isNull()).isTrue();
        assertThat(view.get("photos")).hasSize(2);
        String code = latestOtp(trip.customer());
        assertThat(code).isNotNull();
        String stored = jdbc.queryForObject("select otp_hash from shipment_delivery_confirmations where shipment_id = ?::uuid", String.class, trip.id());
        assertThat(stored).hasSize(64).doesNotContain(code);

        String wrong = code.equals("000000") ? "111111" : "000000";
        send(post("/api/v1/customer/shipments/" + trip.id() + "/confirm-delivery"), trip.customer(), Map.of("otp", wrong))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.code").value("OTP_INVALID")).andExpect(jsonPath("$.data.attemptsLeft").value(4));
        send(post("/api/v1/customer/shipments/" + trip.id() + "/confirm-delivery"), trip.customer(), Map.of("otp", code, "note", "Đã nhận đủ hàng"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(latestOtp(trip.customer())).isNull();   // the inbox copy is redacted after use

        assertThat(jdbc.queryForList("select new_status from shipment_status_histories where shipment_id = ?::uuid order by created_at", String.class, trip.id()))
                .containsSubsequence("ASSIGNED", "DRIVER_ARRIVED", "PICKED_UP", "IN_TRANSIT", "DELIVERED", "COMPLETED");
        assertThat(jdbc.queryForList("select status from shipment_stop_progress where shipment_id = ?::uuid order by sequence_no", String.class, trip.id()))
                .containsExactly("COMPLETED", "COMPLETED");
        assertThat(jdbc.queryForObject("select status from shipment_tracking_sessions where shipment_id = ?::uuid", String.class, trip.id())).isEqualTo("ENDED");
        List<String> customerTypes = new ArrayList<>();
        data(send(get("/api/v1/notifications?size=50"), trip.customer(), null)).get("items").forEach(n -> customerTypes.add(n.get("type").asText()));
        assertThat(customerTypes).contains("TRIP_STATUS_CHANGED", "DELIVERY_OTP");
        List<String> driverTypes = new ArrayList<>();
        data(send(get("/api/v1/notifications?size=50"), trip.driver().session(), null)).get("items").forEach(n -> driverTypes.add(n.get("type").asText()));
        assertThat(driverTypes).contains("DELIVERY_CONFIRMED");
        assertThat(data(send(get("/api/v1/driver/trips?view=COMPLETED"), trip.driver().session(), null)).get("items").findValuesAsText("shipmentCode")).hasSize(1);
    }

    @Test void onlyTheAssignedDriverAndTheOwnerCanAct() throws Exception {
        Trip trip = assignedTrip(1);
        DriverFixture other = approvedDriver(admin, Map.of());
        send(get("/api/v1/driver/trips/" + trip.id()), other.session(), null).andExpect(status().isNotFound());
        send(post("/api/v1/driver/trips/" + trip.id() + "/arrived"), other.session(), Map.of()).andExpect(status().isNotFound());
        assertThat(data(send(get("/api/v1/driver/trips"), other.session(), null)).get("totalElements").asLong()).isZero();
        driverAction(trip, "arrived", Map.of()).andExpect(status().isOk());
        driverAction(trip, "pickup", Map.of()).andExpect(status().isOk());
        driverAction(trip, "start", Map.of()).andExpect(status().isOk());
        send(post("/api/v1/driver/trips/" + trip.id() + "/location"), other.session(),
                Map.of("latitude", 21.0, "longitude", 105.8, "recordedAt", now().toString())).andExpect(status().isNotFound());

        Session stranger = customer();
        send(get("/api/v1/customer/shipments/" + trip.id() + "/trip"), stranger, null).andExpect(status().isNotFound());
        send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking"), stranger, null).andExpect(status().isNotFound());
        send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking/history"), stranger, null).andExpect(status().isNotFound());
        send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking/stream"), stranger, null).andExpect(status().isNotFound());
        send(post("/api/v1/customer/shipments/" + trip.id() + "/confirm-delivery"), stranger, Map.of("otp", "123456")).andExpect(status().isNotFound());
        send(get("/api/v1/driver/trips/" + trip.id()), trip.customer(), null).andExpect(status().isForbidden());
        send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking"), trip.driver().session(), null).andExpect(status().isForbidden());
        // The marketplace never exposes positions.
        assertThat(data(send(get("/api/v1/driver/marketplace/shipments"), other.session(), null)).toString()).doesNotContain("latitude\":21");
        send(get("/api/v1/admin/trips/" + trip.id()), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_TRANSIT"));
        send(get("/api/v1/admin/trips/" + trip.id()), trip.customer(), null).andExpect(status().isForbidden());
    }

    @Test void deliveryCodeLocksExpiresAndCanBeResent() throws Exception {
        Trip trip = delivered(1);
        String code = latestOtp(trip.customer());
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 4; i++)
            send(post("/api/v1/customer/shipments/" + trip.id() + "/confirm-delivery"), trip.customer(), Map.of("otp", wrong)).andExpect(status().isUnprocessableEntity());
        send(post("/api/v1/customer/shipments/" + trip.id() + "/confirm-delivery"), trip.customer(), Map.of("otp", wrong))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("OTP_LOCKED"));
        send(post("/api/v1/customer/shipments/" + trip.id() + "/confirm-delivery"), trip.customer(), Map.of("otp", code))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("OTP_LOCKED"));
        send(post("/api/v1/customer/shipments/" + trip.id() + "/confirm-delivery"), trip.customer(), Map.of("otp", "12ab56")).andExpect(status().isBadRequest());

        send(post("/api/v1/customer/shipments/" + trip.id() + "/delivery-otp/resend"), trip.customer(), null)
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.data.code").value("OTP_RESEND_TOO_SOON"));
        jdbc.update("update shipment_delivery_confirmations set last_sent_at = now() - interval '2 minutes' where shipment_id = ?::uuid", trip.id());
        send(post("/api/v1/customer/shipments/" + trip.id() + "/delivery-otp/resend"), trip.customer(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pending").value(true)).andExpect(jsonPath("$.data.attemptsLeft").value(5));
        String fresh = latestOtp(trip.customer());
        assertThat(fresh).isNotNull();

        // Expired codes are refused even when correct.
        jdbc.update("update shipment_delivery_confirmations set expires_at = now() - interval '1 second' where shipment_id = ?::uuid", trip.id());
        send(post("/api/v1/customer/shipments/" + trip.id() + "/confirm-delivery"), trip.customer(), Map.of("otp", fresh))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("OTP_EXPIRED"));
        assertThat(jdbc.queryForObject("select status from shipments where id = ?::uuid", String.class, trip.id())).isEqualTo("DELIVERED");
    }

    @Test void stopsAreHandledInOrderAndCannotBeSkipped() throws Exception {
        Trip trip = assignedTrip(2);
        driverAction(trip, "arrived", Map.of()).andExpect(status().isOk());
        driverAction(trip, "pickup", Map.of()).andExpect(status().isOk());
        JsonNode detail = data(driverAction(trip, "start", Map.of()).andExpect(status().isOk()));
        assertThat(detail.get("nextAction").asText()).isEqualTo("ARRIVE_STOP");
        String second = detail.at("/stops/1/stopId").asText();
        String last = detail.at("/stops/2/stopId").asText();
        assertThat(detail.get("nextStopId").asText()).isEqualTo(second);

        driverAction(trip, "deliver", Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("STOPS_INCOMPLETE"));
        driverAction(trip, "stops/" + last + "/arrive", Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("STOP_OUT_OF_ORDER"));
        driverAction(trip, "stops/" + second + "/arrive", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.data.nextAction").value("COMPLETE_STOP"));
        driverAction(trip, "stops/" + second + "/complete", Map.of("note", "Đã giao điểm 2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.nextAction").value("DELIVER"));
        driverAction(trip, "stops/" + second + "/complete", Map.of()).andExpect(status().isConflict());
        driverAction(trip, "deliver", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DELIVERED"));
        assertThat(jdbc.queryForList("select status from shipment_stop_progress where shipment_id = ?::uuid order by sequence_no", String.class, trip.id()))
                .containsExactly("COMPLETED", "COMPLETED", "COMPLETED");
        JsonNode view = data(send(get("/api/v1/customer/shipments/" + trip.id() + "/trip"), trip.customer(), null));
        assertThat(view.at("/stops/1/progressNote").asText()).isEqualTo("Đã giao điểm 2");
    }

    @Test void staleAndPausedLocationsAreNeverShownAsLive() throws Exception {
        Trip trip = assignedTrip(1);
        driverAction(trip, "arrived", Map.of()).andExpect(status().isOk());
        driverAction(trip, "pickup", Map.of()).andExpect(status().isOk());
        driverAction(trip, "start", Map.of()).andExpect(status().isOk());
        location(trip, 21.0285, 105.8542, now().minusMinutes(2)).andExpect(status().isOk());
        assertThat(data(send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking"), trip.customer(), null)).get("state").asText()).isEqualTo("STALE");
        redisTemplate.delete("truckgo:trip:" + trip.id() + ":latest-location");   // Redis lost: PostgreSQL answers
        JsonNode t = data(send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking"), trip.customer(), null));
        assertThat(t.get("state").asText()).isEqualTo("STALE");
        assertThat(t.get("ageSeconds").asLong()).isGreaterThanOrEqualTo(110);
        jdbc.update("update shipment_tracking_locations set recorded_at = recorded_at - interval '10 minutes' where shipment_id = ?::uuid", trip.id());
        t = data(send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking"), trip.customer(), null));
        assertThat(t.get("state").asText()).isEqualTo("OFFLINE");
        assertThat(t.get("speed").isNull()).isTrue();

        send(post("/api/v1/driver/trips/" + trip.id() + "/tracking/pause"), trip.driver().session(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PAUSED"));
        clearRateLimit(trip);
        location(trip, 21.03, 105.86, now()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("TRACKING_NOT_ACTIVE"));
        send(post("/api/v1/driver/trips/" + trip.id() + "/tracking/resume"), trip.driver().session(), null).andExpect(status().isOk());
        location(trip, 21.03, 105.86, now()).andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true));
        assertThat(data(send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking"), trip.customer(), null)).get("state").asText()).isEqualTo("LIVE");
    }

    @Test void incidentsAreReportedAndHandledWithoutChangingTheOrder() throws Exception {
        Trip trip = assignedTrip(1);
        driverAction(trip, "arrived", Map.of()).andExpect(status().isOk());
        String photo = uploadPhoto(trip, "INCIDENT_PHOTO");
        JsonNode incident = data(send(post("/api/v1/driver/trips/" + trip.id() + "/incidents"), trip.driver().session(),
                Map.of("type", "VEHICLE_BREAKDOWN", "description", "Xe bị thủng lốp, cần 30 phút", "latitude", 21.02, "longitude", 105.85, "attachments", List.of(photo)))
                .andExpect(status().isCreated()));
        String id = incident.get("id").asText();
        assertThat(incident.get("status").asText()).isEqualTo("OPEN");
        assertThat(incident.get("photos")).hasSize(1);
        send(post("/api/v1/driver/trips/" + trip.id() + "/incidents"), trip.driver().session(), Map.of("type", "NOT_A_TYPE", "description", "abcdef"))
                .andExpect(status().isBadRequest());

        assertThat(data(send(get("/api/v1/customer/shipments/" + trip.id() + "/incidents"), trip.customer(), null).andExpect(status().isOk()))).hasSize(1);
        assertThat(data(send(get("/api/v1/driver/trips?view=INCIDENT"), trip.driver().session(), null)).get("totalElements").asLong()).isEqualTo(1);
        assertThat(data(send(get("/api/v1/admin/incidents?status=OPEN&type=VEHICLE_BREAKDOWN&size=100"), admin, null)).get("items").findValuesAsText("id")).contains(id);

        send(patch("/api/v1/admin/incidents/" + id), admin, Map.of("status", "RESOLVED")).andExpect(status().isBadRequest());
        send(patch("/api/v1/admin/incidents/" + id), admin, Map.of("status", "IN_PROGRESS")).andExpect(status().isOk());
        send(patch("/api/v1/admin/incidents/" + id), admin, Map.of("status", "RESOLVED", "note", "Đã thay lốp")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED")).andExpect(jsonPath("$.data.resolvedAt").isNotEmpty());
        send(patch("/api/v1/admin/incidents/" + id), admin, Map.of("status", "OPEN")).andExpect(status().isConflict());
        send(patch("/api/v1/admin/incidents/" + id), trip.driver().session(), Map.of("status", "CANCELLED", "note", "x")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select status from shipments where id = ?::uuid", String.class, trip.id())).isEqualTo("DRIVER_ARRIVED");
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_logs where target_id = ?::uuid", Long.class, id)).isEqualTo(2);
    }

    @Test void customerStreamReceivesTheirTripEventsOnly() throws Exception {
        Trip trip = assignedTrip(1);
        driverAction(trip, "arrived", Map.of()).andExpect(status().isOk());
        driverAction(trip, "pickup", Map.of()).andExpect(status().isOk());
        driverAction(trip, "start", Map.of()).andExpect(status().isOk());
        MvcResult stream = send(get("/api/v1/customer/shipments/" + trip.id() + "/tracking/stream").accept(MediaType.TEXT_EVENT_STREAM), trip.customer(), null)
                .andExpect(request().asyncStarted()).andReturn();
        Trip unrelated = assignedTrip(1);
        location(trip, 21.0285, 105.8542, now()).andExpect(status().isOk());
        String content = "";
        for (int i = 0; i < 50 && !content.contains("21.0285"); i++) {
            Thread.sleep(100);
            content = stream.getResponse().getContentAsString();
        }
        assertThat(content).contains("event:status").contains("event:location").contains("21.0285");
        assertThat(content).doesNotContain(unrelated.id());
    }
}
