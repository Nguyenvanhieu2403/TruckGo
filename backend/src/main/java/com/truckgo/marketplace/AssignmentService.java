package com.truckgo.marketplace;

import com.truckgo.common.exception.BusinessRuleException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.driver.DriverProfile;
import com.truckgo.notification.NotificationService;
import com.truckgo.notification.NotificationType;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentProperties;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.shipment.ShipmentStatusHistory;
import com.truckgo.shipment.ShipmentStatusHistoryRepository;
import com.truckgo.user.User;
import com.truckgo.user.UserStatus;
import com.truckgo.vehicle.Vehicle;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who carries an order. An order needs {@code vehicleCount} vehicles; each accepted application becomes one
 * {@link ShipmentAssignment} (slot 1..N). The order turns ASSIGNED once every slot is filled; its lead assignment
 * (lowest slot) is copied to shipments.assigned_driver_id/assigned_vehicle_id, which the trip execution of Sprint 6
 * uses unchanged.
 *
 * <p>Every method runs inside the caller's transaction with the order's row lock already held (the same lock apply,
 * cancel and quote acceptance take), so the slot count cannot be exceeded. Schedules are guarded by PostgreSQL
 * exclusion constraints: two concurrent assignments of one driver or vehicle to overlapping trips on different
 * orders cannot both commit, whatever the application checked before.
 *
 * <p>An assignment can be removed or replaced only before the trip starts (order PUBLISHED or ASSIGNED, no stop
 * progress recorded); later problems follow the incident process of Sprint 6.
 */
@Service
public class AssignmentService {
    /** Who acts: a user, or a named system task. */
    public record Actor(UUID userId, String systemName) {
        public static Actor user(UUID id) { return new Actor(id, null); }
        public static Actor system(String name) { return new Actor(null, name); }
        boolean isSystem() { return systemName != null; }
    }

    public record AssignOutcome(ShipmentAssignment assignment, boolean completed, List<DriverShipmentApplication> closedOthers) { }

    static final String CHOSEN_OTHER = CustomerApplicationService.CHOSEN_OTHER;
    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);
    private static final Set<ShipmentStatus> BEFORE_TRIP = Set.of(ShipmentStatus.PUBLISHED, ShipmentStatus.ASSIGNED);

    private final ShipmentAssignmentRepository assignments;
    private final DriverShipmentApplicationRepository applications;
    private final ShipmentRepository shipments;
    private final ShipmentStatusHistoryRepository statusHistory;
    private final ShipmentAssignmentHistoryRepository history;
    private final MarketplaceViews views;
    private final VehicleEligibility eligibility;
    private final ScheduleService schedule;
    private final NotificationService notifications;
    private final MarketplaceCache cache;
    private final ShipmentProperties shipmentProperties;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    public AssignmentService(ShipmentAssignmentRepository assignments, DriverShipmentApplicationRepository applications, ShipmentRepository shipments,
                             ShipmentStatusHistoryRepository statusHistory, ShipmentAssignmentHistoryRepository history, MarketplaceViews views,
                             VehicleEligibility eligibility, ScheduleService schedule, NotificationService notifications, MarketplaceCache cache,
                             ShipmentProperties shipmentProperties, JdbcTemplate jdbc, EntityManager entityManager, Clock clock) {
        this.assignments = assignments;
        this.applications = applications;
        this.shipments = shipments;
        this.statusHistory = statusHistory;
        this.history = history;
        this.views = views;
        this.eligibility = eligibility;
        this.schedule = schedule;
        this.notifications = notifications;
        this.cache = cache;
        this.shipmentProperties = shipmentProperties;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ assign

    /** The locked order can take another driver: published, slots left, before pickup, price accepted by the customer. */
    void requireOpen(Shipment shipment) {
        if (shipment.isAssigned() || shipment.getStatus() == ShipmentStatus.ASSIGNED) throw MarketplaceErrors.fullyAssigned(shipment.getVehicleCount());
        if (shipment.getStatus() != ShipmentStatus.PUBLISHED) throw MarketplaceErrors.notAvailable("Đơn ở trạng thái " + shipment.getStatus() + ", không thể chọn tài xế");
        if (shipment.getPickupTime() == null || !clock.instant().isBefore(shipment.getPickupTime()))
            throw MarketplaceErrors.notAvailable("Đã quá giờ lấy hàng, không thể chọn tài xế");
        // Only an order with a price the customer accepted can be assigned (the price the driver applied for).
        if (views.acceptedQuote(shipment.getId()).isEmpty()) throw MarketplaceErrors.notAvailable("Đơn chưa có báo giá được xác nhận, không thể chọn tài xế");
    }

    /**
     * Turns a pending application into an assignment. The caller has locked the order and checked it is open (PUBLISHED,
     * before pickup, quote accepted). {@code previousDriverId}/{@code action} describe a replacement.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AssignOutcome assign(Shipment shipment, DriverShipmentApplication application, Actor actor, ShipmentAssignmentHistory.Action action,
                                UUID previousDriverId, String reason) {
        // Only people assign drivers (customer or admin); system tasks only ever release.
        if (actor.isSystem()) throw new IllegalArgumentException("System tasks do not assign drivers");
        if (!application.isPending()) throw MarketplaceErrors.notPending();
        DriverProfile driver = views.drivers(List.of(application.getDriverId())).get(application.getDriverId());
        User driverUser = driver == null ? null : views.driverUsers(List.of(driver)).get(driver.getId());
        if (driver == null || !driver.isApproved() || driverUser == null || driverUser.getStatus() != UserStatus.ACTIVE)
            throw MarketplaceErrors.driverNotEligible("Tài xế không còn đủ điều kiện nhận đơn");
        Vehicle vehicle = views.vehicles(List.of(application.getVehicleId())).get(application.getVehicleId());
        List<String> issues = vehicle == null ? List.of("Xe không còn tồn tại") : eligibility.reasons(shipment, vehicle, pickupDate(shipment));
        if (!issues.isEmpty()) throw MarketplaceErrors.vehicleNotEligible(issues);

        List<ShipmentAssignment> active = assignments.findByShipmentIdAndStatusOrderBySlotNoAsc(shipment.getId(), AssignmentStatus.ACTIVE);
        if (active.size() >= shipment.getVehicleCount()) throw MarketplaceErrors.fullyAssigned(shipment.getVehicleCount());
        if (active.stream().anyMatch(a -> a.getDriverId().equals(driver.getId())))
            throw BusinessRuleException.conflict(MarketplaceErrors.DRIVER_ALREADY_ON_SHIPMENT, "Tài xế đã được phân công cho đơn này");
        if (active.stream().anyMatch(a -> a.getVehicleId().equals(vehicle.getId())))
            throw BusinessRuleException.conflict(MarketplaceErrors.VEHICLE_ALREADY_ON_SHIPMENT, "Xe đã được phân công cho đơn này");
        ScheduleService.Period period = schedule.period(shipment);
        List<String> busy = ScheduleService.reasons(schedule.conflicts(driver.getId(), vehicle.getId(), period, shipment.getId()));
        if (!busy.isEmpty()) throw MarketplaceErrors.scheduleConflict(busy);

        Set<Integer> taken = active.stream().map(ShipmentAssignment::getSlotNo).collect(Collectors.toSet());
        int slot = 1;
        while (taken.contains(slot)) slot++;
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        insert(id, shipment, application, slot, period, now, actor);

        application.accept(now);
        applications.flush();
        history.save(new ShipmentAssignmentHistory(shipment.getId(), previousDriverId, driver.getId(), vehicle.getId(), application.getId(), action, reason,
                actor.userId(), now).withAssignment(id));
        notifications.notify(driverUser.getId(), NotificationType.APPLICATION_ACCEPTED, "Bạn đã được chọn cho đơn " + shipment.getShipmentCode(),
                "Bạn được phân công vận chuyển đơn " + shipment.getShipmentCode() + " với xe " + vehicle.getPlateNumber()
                        + (shipment.getVehicleCount() > 1 ? " (xe " + slot + "/" + shipment.getVehicleCount() + ")" : "")
                        + ". Xem chi tiết để có địa chỉ và liên hệ.", "APPLICATION", application.getId(), "APPLICATION_ACCEPTED:" + application.getId());

        boolean completed = active.size() + 1 == shipment.getVehicleCount();
        List<DriverShipmentApplication> closed = List.of();
        if (completed) closed = complete(shipment, actor, now);
        cache.invalidateAfterCommit();
        log.info("Assignment created shipment={} assignment={} driver={} vehicle={} slot={}/{} completed={}", shipment.getId(), id, driver.getId(),
                vehicle.getId(), slot, shipment.getVehicleCount(), completed);
        return new AssignOutcome(assignments.findById(id).orElseThrow(), completed, closed);
    }

    /** Every slot is filled: the order becomes ASSIGNED with its lead assignment, the remaining applicants are told. */
    private List<DriverShipmentApplication> complete(Shipment shipment, Actor actor, Instant now) {
        ShipmentAssignment lead = assignments.findByShipmentIdAndStatusOrderBySlotNoAsc(shipment.getId(), AssignmentStatus.ACTIVE).getFirst();
        if (shipments.assignIfOpen(shipment.getId(), lead.getDriverId(), lead.getVehicleId(), now) != 1) throw MarketplaceErrors.fullyAssigned(shipment.getVehicleCount());
        entityManager.refresh(shipment);
        boolean multi = shipment.getVehicleCount() > 1;
        String why = shipment.isOwnedBy(actor.userId()) ? (multi ? "Khách hàng đã chọn đủ " + shipment.getVehicleCount() + " tài xế" : "Khách hàng chọn tài xế")
                : "Quản trị viên phân công tài xế";
        statusHistory.save(new ShipmentStatusHistory(shipment.getId(), ShipmentStatus.PUBLISHED, ShipmentStatus.ASSIGNED, actor.userId(), why));
        String closeReason = multi ? "Đơn đã đủ số xe cần" : CHOSEN_OTHER;
        List<DriverShipmentApplication> others = applications.findByShipmentIdAndStatus(shipment.getId(), ApplicationStatus.PENDING);
        others.forEach(a -> a.reject(now, closeReason));
        applications.flush();
        Map<UUID, User> users = views.driverUsers(views.drivers(others.stream().map(DriverShipmentApplication::getDriverId).toList()).values());
        others.forEach(a -> notifyClosed(users.get(a.getDriverId()), NotificationType.APPLICATION_REJECTED, shipment, a, closeReason));
        if (multi || !shipment.isOwnedBy(actor.userId()))
            notifications.notify(shipment.getCustomerId(), NotificationType.SHIPMENT_FULLY_ASSIGNED, "Đơn " + shipment.getShipmentCode() + " đã đủ tài xế",
                    "Đã phân công đủ " + shipment.getVehicleCount() + " xe cho đơn " + shipment.getShipmentCode() + ".", "SHIPMENT", shipment.getId(),
                    "SHIPMENT_FULLY_ASSIGNED:" + shipment.getId() + ":" + lead.getId());
        return others;
    }

    private void insert(UUID id, Shipment shipment, DriverShipmentApplication application, int slot, ScheduleService.Period period, Instant now, Actor actor) {
        try {
            jdbc.update("""
                    INSERT INTO shipment_assignments (id, shipment_id, driver_id, vehicle_id, application_id, slot_no, status, busy_period,
                                                      assigned_at, assigned_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', tstzrange(?, ?, '[)'), ?, ?, ?, ?)""",
                    id, shipment.getId(), application.getDriverId(), application.getVehicleId(), application.getId(), slot,
                    Timestamp.from(period.from()), Timestamp.from(period.until()), Timestamp.from(now), actor.userId(), Timestamp.from(now), Timestamp.from(now));
        } catch (DataIntegrityViolationException ex) {
            // The constraints decide when two requests raced past the checks above.
            String message = String.valueOf(ex.getMostSpecificCause().getMessage());
            if (message.contains("ex_assignments_driver_schedule")) throw MarketplaceErrors.scheduleConflict(List.of("Tài xế đã có chuyến khác trùng thời gian"));
            if (message.contains("ex_assignments_vehicle_schedule")) throw MarketplaceErrors.scheduleConflict(List.of("Xe đã có lịch chuyến khác trùng thời gian"));
            if (message.contains("uq_assignments_shipment_driver"))
                throw BusinessRuleException.conflict(MarketplaceErrors.DRIVER_ALREADY_ON_SHIPMENT, "Tài xế đã được phân công cho đơn này");
            if (message.contains("uq_assignments_shipment_vehicle"))
                throw BusinessRuleException.conflict(MarketplaceErrors.VEHICLE_ALREADY_ON_SHIPMENT, "Xe đã được phân công cho đơn này");
            if (message.contains("uq_assignments_shipment_slot")) throw MarketplaceErrors.fullyAssigned(shipment.getVehicleCount());
            throw ex;
        }
    }

    // ------------------------------------------------------------------ release / replace

    /** Removes one driver before the trip starts; an ASSIGNED order goes back to PUBLISHED to find a driver again. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ShipmentAssignment release(Shipment shipment, UUID assignmentId, Actor actor, String reason) {
        ShipmentAssignment assignment = lockActive(shipment, assignmentId);
        requireBeforeTrip(shipment);
        release(shipment, assignment, actor, reason, true);
        log.info("Assignment released shipment={} assignment={} driver={} by={}", shipment.getId(), assignmentId, assignment.getDriverId(),
                actor.isSystem() ? actor.systemName() : actor.userId());
        return assignment;
    }

    /** Removes one driver and assigns a pending applicant in their place, as one step (history REPLACED). */
    @Transactional(propagation = Propagation.MANDATORY)
    public AssignOutcome replace(Shipment shipment, UUID assignmentId, DriverShipmentApplication replacement, Actor actor, String reason) {
        ShipmentAssignment assignment = lockActive(shipment, assignmentId);
        requireBeforeTrip(shipment);
        if (replacement.getDriverId().equals(assignment.getDriverId()))
            throw BusinessRuleException.conflict(MarketplaceErrors.DRIVER_ALREADY_ON_SHIPMENT, "Tài xế thay thế phải khác tài xế hiện tại");
        release(shipment, assignment, actor, reason, false);
        AssignOutcome outcome = assign(shipment, replacement, actor, ShipmentAssignmentHistory.Action.REPLACED, assignment.getDriverId(), reason);
        log.info("Assignment replaced shipment={} old={} new={}", shipment.getId(), assignmentId, outcome.assignment().getId());
        return outcome;
    }

    /** Releases every active assignment of an order that is being cancelled or expired (its status is set by the caller). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int releaseAll(Shipment shipment, Actor actor, String reason) {
        List<ShipmentAssignment> active = assignments.findByShipmentIdAndStatusOrderBySlotNoAsc(shipment.getId(), AssignmentStatus.ACTIVE);
        active.forEach(a -> release(shipment, a, actor, reason, true));
        return active.size();
    }

    /** The trip was delivered: drivers and vehicles are free again from now on. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int finishAll(UUID shipmentId) {
        Instant now = clock.instant();
        List<ShipmentAssignment> active = assignments.findByShipmentIdAndStatusOrderBySlotNoAsc(shipmentId, AssignmentStatus.ACTIVE);
        active.forEach(a -> a.finish(now));
        assignments.flush();
        return active.size();
    }

    private void release(Shipment shipment, ShipmentAssignment assignment, Actor actor, String reason, boolean recordUnassigned) {
        Instant now = clock.instant();
        assignment.release(now, actor.userId(), reason);
        assignments.flush();
        if (assignment.getApplicationId() != null)
            applications.findByIdForUpdate(assignment.getApplicationId()).filter(a -> a.getStatus() == ApplicationStatus.ACCEPTED).ifPresent(a -> a.release(now, reason));
        applications.flush();
        if (recordUnassigned)
            history.save(actor.isSystem()
                    ? ShipmentAssignmentHistory.system(shipment.getId(), assignment.getDriverId(), assignment.getVehicleId(), assignment.getApplicationId(),
                            ShipmentAssignmentHistory.Action.UNASSIGNED, reason, actor.systemName(), now).withAssignment(assignment.getId())
                    : new ShipmentAssignmentHistory(shipment.getId(), assignment.getDriverId(), null, assignment.getVehicleId(), assignment.getApplicationId(),
                            ShipmentAssignmentHistory.Action.UNASSIGNED, reason, actor.userId(), now).withAssignment(assignment.getId()));
        if (shipment.getStatus() == ShipmentStatus.ASSIGNED) {
            // Stop rows are only created when the trip starts; any left over are all PENDING here (checked before).
            jdbc.update("DELETE FROM shipment_stop_progress WHERE shipment_id = ? AND status = 'PENDING'", shipment.getId());
            shipment.reopenForAssignment();
            shipments.flush();
            String why = "Gỡ tài xế" + (reason == null ? "" : ": " + reason);
            statusHistory.save(actor.isSystem()
                    ? ShipmentStatusHistory.system(shipment.getId(), ShipmentStatus.ASSIGNED, ShipmentStatus.PUBLISHED, actor.systemName(), why)
                    : new ShipmentStatusHistory(shipment.getId(), ShipmentStatus.ASSIGNED, ShipmentStatus.PUBLISHED, actor.userId(), why));
        }
        DriverProfile driver = views.drivers(List.of(assignment.getDriverId())).get(assignment.getDriverId());
        User user = driver == null ? null : views.driverUsers(List.of(driver)).get(driver.getId());
        if (user != null)
            notifications.notify(user.getId(), NotificationType.ASSIGNMENT_RELEASED, "Bạn không còn được phân công đơn " + shipment.getShipmentCode(),
                    "Bạn đã được gỡ khỏi đơn " + shipment.getShipmentCode() + (reason == null ? "." : ". Lý do: " + reason), "SHIPMENT", shipment.getId(),
                    "ASSIGNMENT_RELEASED:" + assignment.getId());
        if (!actor.isSystem() && !shipment.isOwnedBy(actor.userId()))
            notifications.notify(shipment.getCustomerId(), NotificationType.ASSIGNMENT_RELEASED, "Quản trị viên đã gỡ một tài xế khỏi đơn " + shipment.getShipmentCode(),
                    "Một tài xế đã được gỡ khỏi đơn " + shipment.getShipmentCode() + (reason == null ? "." : ". Lý do: " + reason), "SHIPMENT", shipment.getId(),
                    "ASSIGNMENT_RELEASED_CUSTOMER:" + assignment.getId());
        cache.invalidateAfterCommit();
    }

    private ShipmentAssignment lockActive(Shipment shipment, UUID assignmentId) {
        ShipmentAssignment assignment = assignments.findByIdForUpdate(assignmentId).filter(a -> a.getShipmentId().equals(shipment.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phân công"));
        if (!assignment.isActive()) throw BusinessRuleException.conflict(MarketplaceErrors.ASSIGNMENT_NOT_ACTIVE, "Phân công này đã kết thúc");
        return assignment;
    }

    /** Only before the driver reaches the first stop; afterwards the trip process of Sprint 6 applies. */
    private void requireBeforeTrip(Shipment shipment) {
        if (!BEFORE_TRIP.contains(shipment.getStatus())) throw MarketplaceErrors.tripStarted(shipment.getStatus());
        Long started = jdbc.queryForObject("SELECT count(*) FROM shipment_stop_progress WHERE shipment_id = ? AND status <> 'PENDING'", Long.class, shipment.getId());
        if (started != null && started > 0) throw MarketplaceErrors.tripStarted(shipment.getStatus());
    }

    void notifyClosed(User user, NotificationType type, Shipment shipment, DriverShipmentApplication application, String reason) {
        if (user == null) return;
        String title = switch (type) {
            case APPLICATION_REJECTED -> "Yêu cầu nhận đơn " + shipment.getShipmentCode() + " không được chọn";
            default -> "Yêu cầu nhận đơn " + shipment.getShipmentCode() + " đã đóng";
        };
        notifications.notify(user.getId(), type, title, reason == null ? title + "." : title + ". Lý do: " + reason, "APPLICATION", application.getId(),
                type.name() + ":" + application.getId());
    }

    private LocalDate pickupDate(Shipment shipment) {
        return shipment.getPickupTime() == null ? null : shipment.getPickupTime().atZone(shipmentProperties.businessZone()).toLocalDate();
    }
}
