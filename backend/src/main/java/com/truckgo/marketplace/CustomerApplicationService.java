package com.truckgo.marketplace;

import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.driver.DriverProfile;
import com.truckgo.marketplace.dto.MarketplaceDtos.ApplicantResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AssignmentOverview;
import com.truckgo.notification.NotificationType;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentProperties;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.user.User;
import com.truckgo.vehicle.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer side: see who applied for an order, choose as many drivers as the order needs vehicles, and remove or
 * replace a driver before the trip starts.
 *
 * <p>Choosing is atomic. {@link #accept} takes the order's row lock (the same lock apply, cancel and quote acceptance
 * take), re-checks the order, application, driver, vehicle and schedules under that lock, then fills one slot; the
 * order becomes ASSIGNED with a conditional update once every slot is filled. Unique and exclusion constraints on
 * shipment_assignments are the last guard. Two tabs accepting different drivers for the last slot therefore end with
 * exactly one assignment; the other gets SHIPMENT_ALREADY_ASSIGNED.
 */
@Service
public class CustomerApplicationService {
    static final String CHOSEN_OTHER = "Khách hàng đã chọn tài xế khác";
    private static final Logger log = LoggerFactory.getLogger(CustomerApplicationService.class);

    private final DriverShipmentApplicationRepository applications;
    private final ShipmentRepository shipments;
    private final MarketplaceViews views;
    private final VehicleEligibility eligibility;
    private final ScheduleService schedule;
    private final AssignmentService assignmentService;
    private final AssignmentViews assignmentViews;
    private final ShipmentProperties shipmentProperties;
    private final Clock clock;

    public CustomerApplicationService(DriverShipmentApplicationRepository applications, ShipmentRepository shipments, MarketplaceViews views,
                                      VehicleEligibility eligibility, ScheduleService schedule, AssignmentService assignmentService,
                                      AssignmentViews assignmentViews, ShipmentProperties shipmentProperties, Clock clock) {
        this.applications = applications;
        this.shipments = shipments;
        this.views = views;
        this.eligibility = eligibility;
        this.schedule = schedule;
        this.assignmentService = assignmentService;
        this.assignmentViews = assignmentViews;
        this.shipmentProperties = shipmentProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ApplicantResponse> list(UUID customerId, UUID shipmentId) {
        Shipment shipment = requireOwned(customerId, shipmentId);
        List<DriverShipmentApplication> rows = applications.findByShipmentIdOrderByAppliedAtAsc(shipmentId);
        Map<UUID, Vehicle> vehicles = views.vehicles(rows.stream().map(DriverShipmentApplication::getVehicleId).toList());
        Map<UUID, DriverProfile> drivers = views.drivers(rows.stream().map(DriverShipmentApplication::getDriverId).toList());
        Map<UUID, User> users = views.driverUsers(drivers.values());
        // Pending first (the customer's to-do), then accepted, then the closed ones.
        return rows.stream()
                .sorted(java.util.Comparator.comparingInt((DriverShipmentApplication a) -> a.getStatus().ordinal()).thenComparing(DriverShipmentApplication::getAppliedAt))
                .map(a -> applicant(shipment, a, drivers.get(a.getDriverId()), users.get(a.getDriverId()), vehicles.get(a.getVehicleId()))).toList();
    }

    @Transactional(readOnly = true)
    public ApplicantResponse get(UUID customerId, UUID shipmentId, UUID applicationId) {
        Shipment shipment = requireOwned(customerId, shipmentId);
        DriverShipmentApplication a = applications.findById(applicationId).filter(x -> x.getShipmentId().equals(shipmentId)).orElseThrow(CustomerApplicationService::applicationNotFound);
        DriverProfile driver = views.drivers(List.of(a.getDriverId())).get(a.getDriverId());
        return applicant(shipment, a, driver, driver == null ? null : views.driverUsers(List.of(driver)).get(driver.getId()), views.vehicles(List.of(a.getVehicleId())).get(a.getVehicleId()));
    }

    /**
     * Chooses one applicant. For an order needing several vehicles each call fills one slot; the order becomes
     * ASSIGNED when the last slot is filled (see {@link AssignmentService}).
     */
    @Transactional
    public ApplicantResponse accept(UUID customerId, UUID shipmentId, UUID applicationId) {
        // Serialise everything that changes this order.
        Shipment shipment = lockOwned(customerId, shipmentId);
        assignmentService.requireOpen(shipment);
        DriverShipmentApplication application = lockApplication(shipmentId, applicationId);
        assignmentService.assign(shipment, application, AssignmentService.Actor.user(customerId), ShipmentAssignmentHistory.Action.ASSIGNED, null, null);
        return get(shipment, application);
    }

    @Transactional(readOnly = true)
    public AssignmentOverview assignments(UUID customerId, UUID shipmentId) { return assignmentViews.overview(requireOwned(customerId, shipmentId)); }

    /** Removes a driver before the trip starts (reason required); an assigned order goes back to the marketplace. */
    @Transactional
    public AssignmentOverview release(UUID customerId, UUID shipmentId, UUID assignmentId, String reason) {
        Shipment shipment = lockOwned(customerId, shipmentId);
        assignmentService.release(shipment, assignmentId, AssignmentService.Actor.user(customerId), reason.trim());
        return assignmentViews.overview(shipment);
    }

    /** Replaces a driver with another pending applicant in one step. */
    @Transactional
    public AssignmentOverview replace(UUID customerId, UUID shipmentId, UUID assignmentId, UUID applicationId, String reason) {
        Shipment shipment = lockOwned(customerId, shipmentId);
        DriverShipmentApplication application = lockApplication(shipmentId, applicationId);
        if (shipment.getPickupTime() == null || !clock.instant().isBefore(shipment.getPickupTime()))
            throw MarketplaceErrors.notAvailable("Đã quá giờ lấy hàng, không thể chọn tài xế");
        if (views.acceptedQuote(shipmentId).isEmpty()) throw MarketplaceErrors.notAvailable("Đơn chưa có báo giá được xác nhận, không thể chọn tài xế");
        assignmentService.replace(shipment, assignmentId, application, AssignmentService.Actor.user(customerId), reason.trim());
        return assignmentViews.overview(shipment);
    }

    @Transactional
    public ApplicantResponse reject(UUID customerId, UUID shipmentId, UUID applicationId, String reason) {
        Shipment shipment = lockOwned(customerId, shipmentId);
        DriverShipmentApplication application = lockApplication(shipmentId, applicationId);
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        application.reject(clock.instant(), why);
        applications.flush();
        DriverProfile driver = views.drivers(List.of(application.getDriverId())).get(application.getDriverId());
        User user = driver == null ? null : views.driverUsers(List.of(driver)).get(driver.getId());
        notifyClosed(user, NotificationType.APPLICATION_REJECTED, shipment, application, why);
        log.info("Driver application rejected application={} shipment={}", applicationId, shipmentId);
        return applicant(shipment, application, driver, user, views.vehicles(List.of(application.getVehicleId())).get(application.getVehicleId()));
    }

    void notifyClosed(User user, NotificationType type, Shipment shipment, DriverShipmentApplication application, String reason) {
        assignmentService.notifyClosed(user, type, shipment, application, reason);
    }

    private ApplicantResponse get(Shipment shipment, DriverShipmentApplication a) {
        DriverProfile driver = views.drivers(List.of(a.getDriverId())).get(a.getDriverId());
        return applicant(shipment, a, driver, driver == null ? null : views.driverUsers(List.of(driver)).get(driver.getId()), views.vehicles(List.of(a.getVehicleId())).get(a.getVehicleId()));
    }

    private Shipment lockOwned(UUID customerId, UUID shipmentId) {
        return shipments.findByIdForUpdate(shipmentId).filter(s -> s.isOwnedBy(customerId)).orElseThrow(CustomerApplicationService::shipmentNotFound);
    }

    private DriverShipmentApplication lockApplication(UUID shipmentId, UUID applicationId) {
        return applications.findByIdForUpdate(applicationId).filter(a -> a.getShipmentId().equals(shipmentId)).orElseThrow(CustomerApplicationService::applicationNotFound);
    }

    private ApplicantResponse applicant(Shipment shipment, DriverShipmentApplication a, DriverProfile driver, User user, Vehicle vehicle) {
        boolean pending = a.isPending();
        List<String> issues = new java.util.ArrayList<>(pending && vehicle != null ? eligibility.reasons(shipment, vehicle, pickupDate(shipment)) : List.of());
        if (pending && vehicle != null) issues.addAll(ScheduleService.reasons(schedule.conflicts(a.getDriverId(), a.getVehicleId(), schedule.period(shipment), shipment.getId())));
        // The driver's phone is shared only with the customer who chose them, while they are on the order.
        boolean revealPhone = a.getStatus() == ApplicationStatus.ACCEPTED;
        return new ApplicantResponse(a.getId(), a.getStatus(), driver == null ? null : views.driver(driver, user, revealPhone), MarketplaceViews.vehicle(vehicle),
                a.getDriverMessage(), a.getResponseReason(), a.getAppliedAt(), a.getRespondedAt(), issues.isEmpty(), issues);
    }

    private Shipment requireOwned(UUID customerId, UUID shipmentId) {
        return shipments.findById(shipmentId).filter(s -> s.isOwnedBy(customerId)).orElseThrow(CustomerApplicationService::shipmentNotFound);
    }

    private LocalDate pickupDate(Shipment shipment) {
        return shipment.getPickupTime() == null ? null : shipment.getPickupTime().atZone(shipmentProperties.businessZone()).toLocalDate();
    }

    private static ResourceNotFoundException shipmentNotFound() { return new ResourceNotFoundException("Không tìm thấy đơn vận chuyển"); }

    private static ResourceNotFoundException applicationNotFound() { return new ResourceNotFoundException("Không tìm thấy yêu cầu nhận đơn"); }
}
