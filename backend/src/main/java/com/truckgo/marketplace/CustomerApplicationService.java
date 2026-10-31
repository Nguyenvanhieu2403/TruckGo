package com.truckgo.marketplace;

import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.driver.DriverProfile;
import com.truckgo.marketplace.dto.MarketplaceDtos.ApplicantResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer side: see who applied for an order and choose one driver.
 *
 * <p>Choosing is atomic. {@link #accept} takes the order's row lock (the same lock apply, cancel and quote acceptance
 * take), re-checks the order, application, driver and vehicle under that lock, then assigns with a conditional
 * update that only matches an open order. The partial unique index on accepted applications is the last guard. Two
 * customers' tabs accepting different drivers at once therefore end with exactly one assignment; the other gets
 * SHIPMENT_ALREADY_ASSIGNED.
 */
@Service
public class CustomerApplicationService {
    static final String CHOSEN_OTHER = "Khách hàng đã chọn tài xế khác";
    private static final Logger log = LoggerFactory.getLogger(CustomerApplicationService.class);

    private final DriverShipmentApplicationRepository applications;
    private final ShipmentRepository shipments;
    private final ShipmentStatusHistoryRepository statusHistory;
    private final ShipmentAssignmentHistoryRepository assignmentHistory;
    private final MarketplaceViews views;
    private final VehicleEligibility eligibility;
    private final NotificationService notifications;
    private final MarketplaceCache cache;
    private final ShipmentProperties shipmentProperties;
    private final EntityManager entityManager;
    private final Clock clock;

    public CustomerApplicationService(DriverShipmentApplicationRepository applications, ShipmentRepository shipments, ShipmentStatusHistoryRepository statusHistory,
                                      ShipmentAssignmentHistoryRepository assignmentHistory, MarketplaceViews views, VehicleEligibility eligibility,
                                      NotificationService notifications, MarketplaceCache cache, ShipmentProperties shipmentProperties,
                                      EntityManager entityManager, Clock clock) {
        this.applications = applications;
        this.shipments = shipments;
        this.statusHistory = statusHistory;
        this.assignmentHistory = assignmentHistory;
        this.views = views;
        this.eligibility = eligibility;
        this.notifications = notifications;
        this.cache = cache;
        this.shipmentProperties = shipmentProperties;
        this.entityManager = entityManager;
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

    @Transactional
    public ApplicantResponse accept(UUID customerId, UUID shipmentId, UUID applicationId) {
        // 1. Serialise everything that changes this order.
        Shipment shipment = shipments.findByIdForUpdate(shipmentId).filter(s -> s.isOwnedBy(customerId)).orElseThrow(CustomerApplicationService::shipmentNotFound);
        if (shipment.isAssigned() || shipment.getStatus() == ShipmentStatus.ASSIGNED) throw MarketplaceErrors.alreadyAssigned();
        if (shipment.getStatus() != ShipmentStatus.PUBLISHED) throw MarketplaceErrors.notAvailable("Đơn ở trạng thái " + shipment.getStatus() + ", không thể chọn tài xế");
        if (shipment.getPickupTime() == null || !clock.instant().isBefore(shipment.getPickupTime()))
            throw MarketplaceErrors.notAvailable("Đã quá giờ lấy hàng, không thể chọn tài xế");

        // 2. The application, the driver and the vehicle must all still qualify at this moment.
        DriverShipmentApplication application = applications.findByIdForUpdate(applicationId)
                .filter(a -> a.getShipmentId().equals(shipmentId)).orElseThrow(CustomerApplicationService::applicationNotFound);
        if (!application.isPending()) throw MarketplaceErrors.notPending();
        DriverProfile driver = views.drivers(List.of(application.getDriverId())).get(application.getDriverId());
        User driverUser = driver == null ? null : views.driverUsers(List.of(driver)).get(driver.getId());
        if (driver == null || !driver.isApproved() || driverUser == null || driverUser.getStatus() != UserStatus.ACTIVE)
            throw MarketplaceErrors.driverNotEligible("Tài xế không còn đủ điều kiện nhận đơn");
        Vehicle vehicle = views.vehicles(List.of(application.getVehicleId())).get(application.getVehicleId());
        List<String> issues = vehicle == null ? List.of("Xe không còn tồn tại") : eligibility.reasons(shipment, vehicle, pickupDate(shipment));
        if (!issues.isEmpty()) throw MarketplaceErrors.vehicleNotEligible(issues);

        // 3. Conditional assignment; 0 rows means someone else got there first.
        Instant now = clock.instant();
        if (shipments.assignIfOpen(shipmentId, driver.getId(), vehicle.getId(), now) != 1) throw MarketplaceErrors.alreadyAssigned();
        entityManager.refresh(shipment);
        application.accept(now);
        List<DriverShipmentApplication> others = applications.findByShipmentIdAndStatus(shipmentId, ApplicationStatus.PENDING).stream()
                .filter(a -> !a.getId().equals(applicationId)).toList();
        others.forEach(a -> a.reject(now, CHOSEN_OTHER));
        try {
            applications.flush();
        } catch (DataIntegrityViolationException ex) {
            throw MarketplaceErrors.alreadyAssigned();
        }
        statusHistory.save(new ShipmentStatusHistory(shipmentId, ShipmentStatus.PUBLISHED, ShipmentStatus.ASSIGNED, customerId, "Khách hàng chọn tài xế"));
        assignmentHistory.save(new ShipmentAssignmentHistory(shipmentId, null, driver.getId(), vehicle.getId(), applicationId,
                ShipmentAssignmentHistory.Action.ASSIGNED, null, customerId, now));

        // 4. Tell everyone who applied, inside the same transaction.
        notifications.notify(driverUser.getId(), NotificationType.APPLICATION_ACCEPTED, "Bạn đã được chọn cho đơn " + shipment.getShipmentCode(),
                "Khách hàng đã chọn bạn vận chuyển đơn " + shipment.getShipmentCode() + " với xe " + vehicle.getPlateNumber()
                        + ". Xem chi tiết để có địa chỉ và liên hệ.", "APPLICATION", applicationId);
        Map<UUID, User> otherUsers = views.driverUsers(views.drivers(others.stream().map(DriverShipmentApplication::getDriverId).toList()).values());
        others.forEach(a -> notifyClosed(otherUsers.get(a.getDriverId()), NotificationType.APPLICATION_REJECTED, shipment, a, CHOSEN_OTHER));
        cache.invalidateAfterCommit();
        log.info("Shipment assigned shipment={} driver={} vehicle={} application={} rejectedOthers={}", shipmentId, driver.getId(), vehicle.getId(), applicationId, others.size());
        return applicant(shipment, application, driver, driverUser, vehicle);
    }

    @Transactional
    public ApplicantResponse reject(UUID customerId, UUID shipmentId, UUID applicationId, String reason) {
        Shipment shipment = shipments.findByIdForUpdate(shipmentId).filter(s -> s.isOwnedBy(customerId)).orElseThrow(CustomerApplicationService::shipmentNotFound);
        DriverShipmentApplication application = applications.findByIdForUpdate(applicationId)
                .filter(a -> a.getShipmentId().equals(shipmentId)).orElseThrow(CustomerApplicationService::applicationNotFound);
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
        if (user == null) return;
        String title = switch (type) {
            case APPLICATION_REJECTED -> "Yêu cầu nhận đơn " + shipment.getShipmentCode() + " không được chọn";
            default -> "Yêu cầu nhận đơn " + shipment.getShipmentCode() + " đã đóng";
        };
        notifications.notify(user.getId(), type, title, reason == null ? title + "." : title + ". Lý do: " + reason, "APPLICATION", application.getId());
    }

    private ApplicantResponse applicant(Shipment shipment, DriverShipmentApplication a, DriverProfile driver, User user, Vehicle vehicle) {
        boolean pending = a.isPending();
        List<String> issues = pending && vehicle != null ? eligibility.reasons(shipment, vehicle, pickupDate(shipment)) : List.of();
        // The driver's phone is shared only with the customer who chose them.
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
