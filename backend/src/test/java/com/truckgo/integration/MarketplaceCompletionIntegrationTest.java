package com.truckgo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.truckgo.marketplace.MarketplaceLifecycle;
import com.truckgo.notification.NotificationService;
import com.truckgo.notification.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sprint 5 completion: multi-vehicle orders, schedules, body types, realtime notifications, system audit, replacing drivers. */
@Testcontainers(disabledWithoutDocker = true)
@org.springframework.context.annotation.Import(PricingIntegrationTest.RouteStub.class)
class MarketplaceCompletionIntegrationTest extends IntegrationTestSupport {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final String VEHICLE_TYPE = "TRUCK_1T";

    @Autowired JdbcTemplate jdbc;
    @Autowired MarketplaceLifecycle lifecycle;
    @Autowired NotificationService notificationService;
    @Autowired TransactionTemplate tx;

    Session admin;
    /** Each test uses its own day so schedules of different tests never overlap. */
    static int dayOffset = 3;

    @BeforeEach void setUp() throws Exception {
        admin = newAdmin();
        if (jdbc.queryForObject("select count(*) from pricing_configs where vehicle_type_code = ? and is_active", Long.class, VEHICLE_TYPE) == 0) {
            Map<String, Object> c = new HashMap<>();
            c.put("name", "Bảng giá"); c.put("vehicleTypeCode", VEHICLE_TYPE);
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

    static Map<String, Object> stop(String type, int seq) {
        return new HashMap<>(Map.of("stopType", type, "sequenceNo", seq, "contactName", "Liên hệ " + seq, "contactPhone", "091234567" + seq,
                "province", seq == 1 ? "Hà Nội" : "Hải Phòng", "district", seq == 1 ? "Cầu Giấy" : "Lê Chân", "addressDetail", "Số " + seq));
    }

    static OffsetDateTime day(int offset, int hour) {
        return OffsetDateTime.now(ZoneOffset.ofHours(7)).plusDays(offset).withHour(hour).truncatedTo(ChronoUnit.HOURS);
    }

    /** Published, quote accepted: 600 kg, pickup at the given time, delivery deadline 4 hours later. */
    String openShipment(Session customer, OffsetDateTime pickup, int vehicles, String bodyType) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("pickupTime", pickup.toString());
        body.put("deliveryDeadline", pickup.plusHours(4).toString());
        body.put("vehicleTypeCode", VEHICLE_TYPE);
        body.put("vehicleCount", vehicles);
        if (bodyType != null) body.put("bodyTypeCode", bodyType);
        body.put("stops", List.of(stop("PICKUP", 1), stop("DELIVERY", 2)));
        body.put("items", List.of(Map.of("itemName", "Máy giặt", "category", "ELECTRONICS", "quantity", 10, "weightPerUnitKg", "60",
                "lengthCm", "60", "widthCm", "60", "heightCm", "90")));
        String id = data(send(post("/api/v1/shipments/draft"), customer, body).andExpect(status().isCreated())).get("id").asText();
        send(post("/api/v1/shipments/" + id + "/pricing/preview"), customer, null).andExpect(status().isOk());
        send(post("/api/v1/shipments/" + id + "/pricing/accept"), customer, null).andExpect(status().isOk());
        send(post("/api/v1/shipments/" + id + "/publish"), customer, null).andExpect(status().isOk());
        return id;
    }

    ResultActions apply(DriverFixture driver, String shipmentId, String vehicleId) throws Exception {
        return send(post("/api/v1/driver/marketplace/shipments/" + shipmentId + "/applications"), driver.session(), Map.of("vehicleId", vehicleId));
    }

    String applied(DriverFixture driver, String shipmentId) throws Exception {
        return data(apply(driver, shipmentId, driver.vehicleId()).andExpect(status().isCreated())).get("id").asText();
    }

    ResultActions accept(Session customer, String shipmentId, String applicationId) throws Exception {
        return send(post("/api/v1/customer/shipments/" + shipmentId + "/applications/" + applicationId + "/accept"), customer, null);
    }

    JsonNode overview(Session customer, String shipmentId) throws Exception {
        return data(send(get("/api/v1/customer/shipments/" + shipmentId + "/assignments"), customer, null).andExpect(status().isOk()));
    }

    String shipmentStatus(String shipmentId) { return jdbc.queryForObject("select status from shipments where id = ?::uuid", String.class, shipmentId); }

    // ------------------------------------------------------------------ multi-vehicle

    @Test void multiVehicleOrderIsFilledSlotBySlotAndNeverBeyondTheCount() throws Exception {
        Session customer = customer();
        String id = openShipment(customer, day(dayOffset++, 8), 2, null);
        DriverFixture a = approvedDriver(admin, Map.of());
        DriverFixture b = approvedDriver(admin, Map.of());
        DriverFixture c = approvedDriver(admin, Map.of());

        // Listed for drivers with what is needed and what is already filled.
        JsonNode row = findListing(a.session(), id);
        assertThat(row.at("/shipment/vehicleCount").asInt()).isEqualTo(2);
        assertThat(row.at("/shipment/assignedVehicleCount").asInt()).isZero();

        String appA = applied(a, id);
        String appB = applied(b, id);
        String appC = applied(c, id);
        accept(customer, id, appA).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        // Still open: one vehicle missing. The listing (cache dropped after commit) shows 1 of 2.
        assertThat(shipmentStatus(id)).isEqualTo("PUBLISHED");
        assertThat(findListing(c.session(), id).at("/shipment/assignedVehicleCount").asInt()).isEqualTo(1);
        JsonNode progress = overview(customer, id);
        assertThat(progress.get("assignedCount").asInt()).isEqualTo(1);
        assertThat(progress.get("remaining").asInt()).isEqualTo(1);
        // The driver already on the order cannot take its second slot; a second accept of the same application fails.
        accept(customer, id, appA).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("APPLICATION_NOT_PENDING"));

        accept(customer, id, appB).andExpect(status().isOk());
        assertThat(shipmentStatus(id)).isEqualTo("ASSIGNED");
        JsonNode detail = data(send(get("/api/v1/shipments/" + id), customer, null));
        assertThat(detail.get("assignments")).hasSize(2);
        assertThat(detail.at("/assignment/driverId").asText()).isEqualTo(a.profileId());   // lead = slot 1, runs the trip
        assertThat(detail.at("/assignments/1/slotNo").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from driver_shipment_applications where id = ?::uuid", String.class, appC)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select response_reason from driver_shipment_applications where id = ?::uuid", String.class, appC)).isEqualTo("Đơn đã đủ số xe cần");
        accept(customer, id, appC).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("SHIPMENT_ALREADY_ASSIGNED"));
        assertThat(jdbc.queryForObject("select count(*) from shipment_assignments where shipment_id = ?::uuid and status = 'ACTIVE'", Long.class, id)).isEqualTo(2);
        // Both drivers see the exact addresses: each is on the order.
        JsonNode second = data(send(get("/api/v1/driver/applications/" + appB), b.session(), null));
        assertThat(second.at("/stops/0/fullAddress").asText()).isNotBlank();
        assertThat(notificationTypes(customer)).contains("SHIPMENT_FULLY_ASSIGNED");
    }

    @Test void twoCustomersTabsRacingForTheLastVehicleAssignExactlyOne() throws Exception {
        Session customer = customer();
        String id = openShipment(customer, day(dayOffset++, 8), 2, null);
        DriverFixture first = approvedDriver(admin, Map.of());
        accept(customer, id, applied(first, id)).andExpect(status().isOk());
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < 4; i++) candidates.add(applied(approvedDriver(admin, Map.of()), id));

        List<Integer> codes = race(candidates.stream().map(app -> (Callable<Integer>) () -> accept(customer, id, app).andReturn().getResponse().getStatus()).toList());
        assertThat(codes).containsOnly(200, 409);
        assertThat(codes.stream().filter(c -> c == 200)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from shipment_assignments where shipment_id = ?::uuid and status = 'ACTIVE'", Long.class, id)).isEqualTo(2);
        assertThat(shipmentStatus(id)).isEqualTo("ASSIGNED");
    }

    // ------------------------------------------------------------------ schedules

    @Test void aDriverCannotTakeTwoOverlappingTripsEvenWhenAcceptedAtTheSameMoment() throws Exception {
        int d = dayOffset++;
        Session c1 = customer();
        Session c2 = customer();
        String morning = openShipment(c1, day(d, 8), 1, null);
        String overlapping = openShipment(c2, day(d, 10), 1, null);     // 10:00 is inside 08:00–12:00 + 1 h buffer
        String evening = openShipment(c2, day(d, 18), 1, null);
        DriverFixture driver = approvedDriver(admin, Map.of());

        // Applying to both is allowed while neither is assigned; the check happens again when the customers choose.
        String app1 = applied(driver, morning);
        String app2 = applied(driver, overlapping);
        List<Integer> codes = race(List.of(() -> accept(c1, morning, app1).andReturn().getResponse().getStatus(),
                () -> accept(c2, overlapping, app2).andReturn().getResponse().getStatus()));
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("""
                select count(*) from shipment_assignments where driver_id = ?::uuid and status = 'ACTIVE'""", Long.class, driver.profileId())).isEqualTo(1);

        String winner = "ASSIGNED".equals(shipmentStatus(morning)) ? morning : overlapping;
        String loser = winner.equals(morning) ? overlapping : morning;
        Session loserCustomer = winner.equals(morning) ? c2 : c1;
        String loserApp = winner.equals(morning) ? app2 : app1;
        accept(loserCustomer, loser, loserApp).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("SCHEDULE_CONFLICT"))
                .andExpect(jsonPath("$.data.reasons[0]").value(org.hamcrest.Matchers.startsWith("Tài xế đã có chuyến")));
        // The applicant list tells the customer why.
        JsonNode applicants = data(send(get("/api/v1/customer/shipments/" + loser + "/applications"), loserCustomer, null));
        assertThat(applicants.get(0).get("vehicleIssues").toString()).contains("trùng thời gian");

        // New applications to an overlapping trip are refused up front; the evening trip is fine.
        DriverFixture other = approvedDriver(admin, Map.of());
        String extra = openShipment(c1, day(d, 9), 1, null);
        JsonNode marketplace = data(send(get("/api/v1/driver/marketplace/shipments/" + extra), driver.session(), null));
        assertThat(marketplace.get("canApply").asBoolean()).isFalse();
        assertThat(marketplace.get("unavailableReason").asText()).isEqualTo("Bạn đã có chuyến khác trùng thời gian với đơn này");
        assertThat(marketplace.get("vehicles").get(0).get("reasons").toString()).contains("Xe đã có lịch chuyến");
        apply(driver, extra, driver.vehicleId()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("SCHEDULE_CONFLICT"));
        apply(other, extra, other.vehicleId()).andExpect(status().isCreated());
        accept(c2, evening, applied(driver, evening)).andExpect(status().isOk());

        // Admin schedule view lists the driver's busy periods.
        JsonNode schedule = data(send(get("/api/v1/admin/marketplace/schedules?driverId=" + driver.profileId()), admin, null).andExpect(status().isOk()));
        assertThat(schedule.get("items")).hasSize(2);
    }

    @Test void theDatabaseRejectsAVehicleOnTwoOverlappingAssignments() throws Exception {
        int d = dayOffset++;
        Session c = customer();
        String one = openShipment(c, day(d, 8), 1, null);
        String two = openShipment(c, day(d, 9), 1, null);
        DriverFixture driver = approvedDriver(admin, Map.of());
        DriverFixture other = approvedDriver(admin, Map.of());
        accept(c, one, applied(driver, one)).andExpect(status().isOk());
        // Bypassing every application check: the exclusion constraint alone refuses the same vehicle (different driver) overlapping.
        assertThatThrownBy(() -> jdbc.update("""
                insert into shipment_assignments (id, shipment_id, driver_id, vehicle_id, slot_no, status, busy_period, assigned_at, assigned_by, created_at, updated_at)
                select gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, 1, 'ACTIVE', tstzrange(pickup_time, pickup_time + interval '2 hours'), now(), customer_id, now(), now()
                  from shipments where id = ?::uuid""", two, other.profileId(), driver.vehicleId(), two))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ex_assignments_vehicle_schedule");
    }

    // ------------------------------------------------------------------ body types and approval

    @Test void bodyTypeAndApprovalAreCheckedForTheDriversVehicle() throws Exception {
        Session c = customer();
        String tarp = openShipment(c, day(dayOffset++, 8), 1, "TARPAULIN");
        DriverFixture closed = approvedDriver(admin, Map.of());      // CLOSED_BOX
        apply(closed, tarp, closed.vehicleId()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.code").value("VEHICLE_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.data.reasons[0]").value("Loại thùng xe không đúng yêu cầu của đơn"));
        DriverFixture tarpaulin = approvedDriver(admin, Map.of("bodyTypeCode", "TARPAULIN"));
        apply(tarpaulin, tarp, tarpaulin.vehicleId()).andExpect(status().isCreated());

        // A vehicle waiting for review cannot be used.
        String pending = data(send(post("/api/v1/vehicles"), closed.session(), Map.of("plateNumber", uniquePlate(), "vehicleType", "SMALL_TRUCK", "bodyTypeCode", "TARPAULIN",
                "maxPayloadKg", 1500, "lengthCm", 420, "widthCm", 180, "heightCm", 190, "registrationExpiryDate", java.time.LocalDate.now().plusYears(1).toString()))
                .andExpect(status().isCreated())).get("id").asText();
        apply(closed, tarp, pending).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.reasons[0]").value("Xe chưa được duyệt"));

        // A vehicle registered before body types existed is not given an invented one.
        jdbc.update("update vehicles set body_type_code = null where id = ?::uuid", closed.vehicleId());
        JsonNode mine = data(send(get("/api/v1/vehicles/" + closed.vehicleId()), closed.session(), null));
        assertThat(mine.get("bodyTypeCode").isNull()).isTrue();
        apply(closed, tarp, closed.vehicleId()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.reasons[0]").value(org.hamcrest.Matchers.startsWith("Xe chưa khai báo loại thùng")));
    }

    // ------------------------------------------------------------------ remove / replace

    @Test void customerRemovesAndReplacesADriverBeforeTheTripButNotDuringIt() throws Exception {
        Session c = customer();
        String id = openShipment(c, day(dayOffset++, 8), 1, null);
        DriverFixture first = approvedDriver(admin, Map.of());
        accept(c, id, applied(first, id)).andExpect(status().isOk());
        String assignmentId = overview(c, id).at("/assignments/0/id").asText();

        // Reason required.
        send(post("/api/v1/customer/shipments/" + id + "/assignments/" + assignmentId + "/release"), c, Map.of("reason", " "))
                .andExpect(status().isBadRequest());
        JsonNode released = data(send(post("/api/v1/customer/shipments/" + id + "/assignments/" + assignmentId + "/release"), c,
                Map.of("reason", "Tài xế báo bận")).andExpect(status().isOk()));
        assertThat(released.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(released.get("assignedCount").asInt()).isZero();
        assertThat(released.at("/history/1/action").asText()).isEqualTo("UNASSIGNED");
        assertThat(released.at("/history/1/reason").asText()).isEqualTo("Tài xế báo bận");
        assertThat(jdbc.queryForObject("select assigned_driver_id from shipments where id = ?::uuid", UUID.class, id)).isNull();
        assertThat(notificationTypes(first.session())).contains("ASSIGNMENT_RELEASED");
        assertThat(jdbc.queryForObject("select status from driver_shipment_applications where driver_id = ?::uuid and shipment_id = ?::uuid",
                String.class, first.profileId(), id)).isEqualTo("RELEASED");

        // Back on the marketplace; a new driver applies and replaces nobody (slot free) -> assigned again.
        DriverFixture second = approvedDriver(admin, Map.of());
        accept(c, id, applied(second, id)).andExpect(status().isOk());
        assertThat(shipmentStatus(id)).isEqualTo("ASSIGNED");

        // Replace in one step with a pending applicant: only possible while there is one; here we free the slot via admin.
        String current = overview(c, id).at("/assignments/0/id").asText();
        JsonNode adminView = data(send(post("/api/v1/admin/marketplace/shipments/" + id + "/assignments/" + current + "/release"), admin,
                Map.of("reason", "Hồ sơ tài xế cần kiểm tra lại")).andExpect(status().isOk()));
        assertThat(adminView.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_logs where action = 'ASSIGNMENT_RELEASED' and target_id = ?::uuid", Long.class, id)).isEqualTo(1);
        assertThat(notificationTypes(c)).contains("ASSIGNMENT_RELEASED");

        DriverFixture third = approvedDriver(admin, Map.of());
        accept(c, id, applied(third, id)).andExpect(status().isOk());
        // The trip starts: from now on drivers cannot be swapped.
        send(post("/api/v1/driver/trips/" + id + "/arrived"), third.session(), Map.of()).andExpect(status().isOk());
        send(post("/api/v1/driver/trips/" + id + "/pickup"), third.session(), Map.of()).andExpect(status().isOk());
        send(post("/api/v1/driver/trips/" + id + "/start"), third.session(), Map.of()).andExpect(status().isOk());
        assertThat(shipmentStatus(id)).isEqualTo("IN_TRANSIT");
        JsonNode during = overview(c, id);
        assertThat(during.get("canChange").asBoolean()).isFalse();
        send(post("/api/v1/customer/shipments/" + id + "/assignments/" + during.at("/assignments/0/id").asText() + "/release"), c, Map.of("reason", "Đổi ý"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("TRIP_ALREADY_STARTED"));

        // Delivered: the driver and vehicle are free again (assignment finished).
        send(post("/api/v1/driver/trips/" + id + "/deliver"), third.session(), Map.of()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from shipment_assignments where shipment_id = ?::uuid and driver_id = ?::uuid", String.class, id, third.profileId()))
                .isEqualTo("FINISHED");
        assertThat(jdbc.queryForList("select action from shipment_assignment_histories where shipment_id = ?::uuid order by created_at", String.class, id))
                .containsExactly("ASSIGNED", "UNASSIGNED", "ASSIGNED", "UNASSIGNED", "ASSIGNED");
    }

    @Test void replacingInOneStepUsesAPendingApplicant() throws Exception {
        Session c = customer();
        String id = openShipment(c, day(dayOffset++, 8), 2, null);
        DriverFixture a = approvedDriver(admin, Map.of());
        DriverFixture b = approvedDriver(admin, Map.of());
        accept(c, id, applied(a, id)).andExpect(status().isOk());
        String appB = applied(b, id);
        String slot = overview(c, id).at("/assignments/0/id").asText();
        JsonNode after = data(send(post("/api/v1/customer/shipments/" + id + "/assignments/" + slot + "/replace"), c,
                Map.of("applicationId", appB, "reason", "Xe tài xế A hỏng")).andExpect(status().isOk()));
        assertThat(after.get("assignedCount").asInt()).isEqualTo(1);
        assertThat(after.at("/assignments/0/driver/driverId").asText()).isEqualTo(b.profileId());
        JsonNode last = after.get("history").get(after.get("history").size() - 1);
        assertThat(last.get("action").asText()).isEqualTo("REPLACED");
        assertThat(last.get("previousDriverId").asText()).isEqualTo(a.profileId());
        assertThat(last.get("newDriverId").asText()).isEqualTo(b.profileId());
    }

    // ------------------------------------------------------------------ system audit

    @Test void expiryJobIsRecordedAsTheSystemAndReleasesPartialAssignments() throws Exception {
        Session c = customer();
        String id = openShipment(c, day(dayOffset++, 8), 2, null);
        DriverFixture a = approvedDriver(admin, Map.of());
        accept(c, id, applied(a, id)).andExpect(status().isOk());
        jdbc.update("update shipments set published_at = now() - interval '2 minutes', pickup_time = now() - interval '1 minute', delivery_deadline = now() + interval '3 hours' where id = ?::uuid", id);
        assertThat(lifecycle.expireIfDue(UUID.fromString(id))).isTrue();

        Map<String, Object> history = jdbc.queryForMap("""
                select changed_by, actor_type, system_actor from shipment_status_histories where shipment_id = ?::uuid and new_status = 'EXPIRED'""", id);
        assertThat(history.get("changed_by")).isNull();
        assertThat(history.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(history.get("system_actor")).isEqualTo(MarketplaceLifecycle.EXPIRY_ACTOR);
        assertThat(jdbc.queryForMap("""
                select status, released_by from shipment_assignments where shipment_id = ?::uuid""", id)).containsEntry("status", "RELEASED").containsEntry("released_by", null);
        assertThat(jdbc.queryForObject("select actor_type from shipment_assignment_histories where shipment_id = ?::uuid and action = 'UNASSIGNED'", String.class, id))
                .isEqualTo("SYSTEM");
        assertThat(notificationTypes(a.session())).contains("ASSIGNMENT_RELEASED");
        JsonNode detail = data(send(get("/api/v1/shipments/" + id), c, null));
        JsonNode expired = detail.get("statusHistory").get(detail.get("statusHistory").size() - 1);
        assertThat(expired.get("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(expired.get("changedBy").isNull()).isTrue();
        // The database refuses a "system" row that also names a user, or a user row without one.
        assertThatThrownBy(() -> jdbc.update("""
                insert into shipment_status_histories (id, shipment_id, old_status, new_status, changed_by, actor_type, created_at)
                values (gen_random_uuid(), ?::uuid, 'PUBLISHED', 'EXPIRED', null, 'USER', now())""", id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ notifications

    @Test void notificationsStreamToTheirRecipientReplayAfterReconnectAndAreNotDuplicated() throws Exception {
        Session c = customer();
        String id = openShipment(c, day(dayOffset++, 8), 1, null);
        DriverFixture driver = approvedDriver(admin, Map.of());
        DriverFixture stranger = approvedDriver(admin, Map.of());
        MvcResult customerStream = mvc.perform(get("/api/v1/notifications/stream").header("Authorization", bearer(c))).andExpect(request().asyncStarted()).andReturn();
        MvcResult strangerStream = mvc.perform(get("/api/v1/notifications/stream").header("Authorization", bearer(stranger.session())))
                .andExpect(request().asyncStarted()).andReturn();

        String app = applied(driver, id);
        eventually(() -> assertThat(content(customerStream)).contains("event:notification").contains("APPLICATION_RECEIVED"));
        assertThat(content(strangerStream)).doesNotContain("APPLICATION_RECEIVED");

        // Disconnected while the driver was chosen: reconnecting with the last seen id replays what was missed.
        String lastSeen = data(send(get("/api/v1/notifications?size=1"), c, null)).at("/items/0/id").asText();
        MvcResult driverStream = mvc.perform(get("/api/v1/notifications/stream").header("Authorization", bearer(driver.session())))
                .andExpect(request().asyncStarted()).andReturn();
        accept(c, id, app).andExpect(status().isOk());
        eventually(() -> assertThat(content(driverStream)).contains("APPLICATION_ACCEPTED"));
        MvcResult reconnect = mvc.perform(get("/api/v1/notifications/stream").header("Authorization", bearer(c)).header("Last-Event-ID", lastSeen))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(content(reconnect)).contains("\"replayed\"");

        // The same business event delivered twice produces one notification.
        UUID customerId = UUID.fromString(jdbc.queryForObject("select customer_id from shipments where id = ?::uuid", String.class, id));
        for (int i = 0; i < 2; i++)
            tx.executeWithoutResult(s -> notificationService.notify(customerId, NotificationType.SHIPMENT_EXPIRED, "Thử", "Thử", "SHIPMENT", UUID.fromString(id), "TEST:" + id));
        assertThat(jdbc.queryForObject("select count(*) from notifications where event_key = ?", Long.class, "TEST:" + id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ admin

    @Test void adminSeesOrdersStillMissingDrivers() throws Exception {
        Session c = customer();
        String id = openShipment(c, day(dayOffset++, 8), 3, null);
        accept(c, id, applied(approvedDriver(admin, Map.of()), id)).andExpect(status().isOk());
        JsonNode rows = data(send(get("/api/v1/admin/marketplace/shipments?understaffed=true&size=100"), admin, null).andExpect(status().isOk())).get("items");
        JsonNode row = null;
        for (JsonNode r : rows) if (r.get("id").asText().equals(id)) row = r;
        assertThat(row).isNotNull();
        assertThat(row.get("vehicleCount").asInt()).isEqualTo(3);
        assertThat(row.get("assignedVehicleCount").asInt()).isEqualTo(1);
        JsonNode adminOverview = data(send(get("/api/v1/admin/marketplace/shipments/" + id + "/assignments"), admin, null).andExpect(status().isOk()));
        assertThat(adminOverview.get("remaining").asInt()).isEqualTo(2);
        // Customers cannot use the admin endpoints.
        send(get("/api/v1/admin/marketplace/shipments/" + id + "/assignments"), c, null).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ utilities

    JsonNode findListing(Session driver, String id) throws Exception {
        for (JsonNode item : data(send(get("/api/v1/driver/marketplace/shipments?size=100"), driver, null).andExpect(status().isOk())).get("items"))
            if (item.at("/shipment/id").asText().equals(id)) return item;
        throw new AssertionError("Order " + id + " not listed");
    }

    List<String> notificationTypes(Session session) throws Exception {
        List<String> types = new ArrayList<>();
        data(send(get("/api/v1/notifications?size=100"), session, null).andExpect(status().isOk())).get("items").forEach(n -> types.add(n.get("type").asText()));
        return types;
    }

    static String content(MvcResult result) {
        try { return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static void eventually(Runnable block) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            try { block.run(); return; }
            catch (AssertionError e) {
                if (System.nanoTime() > deadline) throw e;
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    static List<Integer> race(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) futures.add(pool.submit(() -> { start.await(); return call.call(); }));
            start.countDown();
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
