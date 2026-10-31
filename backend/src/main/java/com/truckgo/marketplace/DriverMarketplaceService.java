package com.truckgo.marketplace;

import com.truckgo.common.exception.BusinessRuleException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverProfileRepository;
import com.truckgo.marketplace.dto.MarketplaceDtos.ApplyRequest;
import com.truckgo.marketplace.dto.MarketplaceDtos.DriverApplicationResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.MarketplaceListItem;
import com.truckgo.marketplace.dto.MarketplaceDtos.MarketplaceShipmentDetail;
import com.truckgo.marketplace.dto.MarketplaceDtos.MarketplaceShipmentSummary;
import com.truckgo.marketplace.dto.MarketplaceDtos.MyApplicationBrief;
import com.truckgo.marketplace.dto.MarketplaceDtos.VehicleOption;
import com.truckgo.notification.NotificationService;
import com.truckgo.notification.NotificationType;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentMapper;
import com.truckgo.shipment.ShipmentProperties;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.shipment.dto.ShipmentResponses.ShipmentDetail;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import com.truckgo.vehicle.Vehicle;
import com.truckgo.vehicle.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Driver side of the marketplace. The driver is always resolved from the authenticated user; a driver can see an
 * order only while it is open, or when they have applied for it.
 */
@Service
public class DriverMarketplaceService {
    private static final Logger log = LoggerFactory.getLogger(DriverMarketplaceService.class);

    private final MarketplaceQuery query;
    private final MarketplaceCache cache;
    private final MarketplaceViews views;
    private final VehicleEligibility eligibility;
    private final DriverShipmentApplicationRepository applications;
    private final ShipmentRepository shipments;
    private final DriverProfileRepository drivers;
    private final VehicleRepository vehicles;
    private final UserRepository users;
    private final ShipmentMapper shipmentMapper;
    private final NotificationService notifications;
    private final MarketplaceProperties properties;
    private final ShipmentProperties shipmentProperties;
    private final Clock clock;

    public DriverMarketplaceService(MarketplaceQuery query, MarketplaceCache cache, MarketplaceViews views, VehicleEligibility eligibility,
                                    DriverShipmentApplicationRepository applications, ShipmentRepository shipments, DriverProfileRepository drivers,
                                    VehicleRepository vehicles, UserRepository users, ShipmentMapper shipmentMapper, NotificationService notifications,
                                    MarketplaceProperties properties, ShipmentProperties shipmentProperties, Clock clock) {
        this.query = query;
        this.cache = cache;
        this.views = views;
        this.eligibility = eligibility;
        this.applications = applications;
        this.shipments = shipments;
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.users = users;
        this.shipmentMapper = shipmentMapper;
        this.notifications = notifications;
        this.properties = properties;
        this.shipmentProperties = shipmentProperties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ browse

    @Transactional(readOnly = true)
    public PageResponse<MarketplaceListItem> list(UUID userId, MarketplaceFilter filter) {
        PageResponse<MarketplaceShipmentSummary> page = cache.listing(filter, () -> query.openShipments(filter, clock.instant(), properties.applicationCutoff()));
        Map<UUID, ApplicationStatus> mine = drivers.findByUserId(userId)
                .map(d -> applications.findByDriverIdAndShipmentIdIn(d.getId(), page.items().stream().map(MarketplaceShipmentSummary::id).toList()))
                .orElse(List.of()).stream()
                .collect(Collectors.toMap(DriverShipmentApplication::getShipmentId, DriverShipmentApplication::getStatus, DriverMarketplaceService::moreRelevant));
        return new PageResponse<>(page.items().stream().map(s -> new MarketplaceListItem(s, mine.get(s.id()))).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }

    @Transactional(readOnly = true)
    public MarketplaceShipmentDetail detail(UUID userId, UUID shipmentId) {
        Optional<DriverProfile> driver = drivers.findByUserId(userId);
        Shipment shipment = shipments.findById(shipmentId).orElseThrow(DriverMarketplaceService::notFound);
        List<DriverShipmentApplication> mine = driver.map(d -> applications.findByDriverIdAndShipmentIdOrderByAppliedAtDesc(d.getId(), shipmentId)).orElse(List.of());
        Optional<String> unavailable = unavailableReason(shipment);
        if (unavailable.isPresent() && mine.isEmpty()) throw notFound();

        boolean assignedToMe = driver.map(d -> d.getId().equals(shipment.getAssignedDriverId())).orElse(false);
        DriverShipmentApplication latest = mine.isEmpty() ? null : mine.getFirst();
        List<Vehicle> myVehicles = driver.map(d -> vehicles.findByDriverIdOrderByCreatedAtDesc(d.getId())).orElse(List.of());
        LocalDate pickupDate = pickupDate(shipment);
        List<VehicleOption> options = myVehicles.stream().map(v -> {
            List<String> reasons = eligibility.reasons(shipment, v, pickupDate);
            return new VehicleOption(v.getId(), v.getPlateNumber(), v.getVehicleType(), v.getMaxPayloadKg(), v.getLengthCm(), v.getWidthCm(), v.getHeightCm(), reasons.isEmpty(), reasons);
        }).toList();
        boolean approved = driver.map(DriverProfile::isApproved).orElse(false);
        boolean hasActive = mine.stream().anyMatch(a -> a.getStatus().isActive());
        boolean rejectedBefore = mine.stream().anyMatch(a -> a.getStatus() == ApplicationStatus.REJECTED);
        String reason = unavailable.orElseGet(() -> !approved ? "Hồ sơ tài xế chưa được duyệt"
                : hasActive ? null
                : rejectedBefore ? "Khách hàng đã từ chối yêu cầu của bạn cho đơn này"
                : options.stream().noneMatch(VehicleOption::eligible) ? "Bạn chưa có xe đáp ứng yêu cầu của đơn" : null);
        boolean canApply = reason == null && !hasActive;

        ShipmentDetail full = shipmentMapper.toDetail(shipment, List.of(), List.of(), false);
        Vehicle latestVehicle = latest == null ? null : myVehicles.stream().filter(v -> v.getId().equals(latest.getVehicleId())).findFirst().orElse(null);
        return new MarketplaceShipmentDetail(shipment.getId(), shipment.getShipmentCode(), shipment.getStatus(), shipment.getPickupTime(), shipment.getDeliveryDeadline(),
                deadline(shipment), full.vehicle(), shipment.getTotalQuantity(), shipment.getTotalWeightKg(), shipment.getTotalVolumeM3(), shipment.getCustomerNote(),
                shipment.getEstimatedPrice(), views.distanceKm(shipmentId), assignedToMe, MarketplaceViews.stops(shipment, assignedToMe), full.items(), full.services(),
                approved, canApply, canApply ? null : reason, latest == null ? null : brief(latest, latestVehicle), options);
    }

    // ------------------------------------------------------------------ apply / withdraw

    @Transactional
    public DriverApplicationResponse apply(UUID userId, UUID shipmentId, ApplyRequest request) {
        DriverProfile driver = requireEligibleDriver(userId);
        // Same row lock as the customer's accept: an application can never slip in after the order was assigned.
        Shipment shipment = shipments.findByIdForUpdate(shipmentId).filter(s -> s.getStatus() != ShipmentStatus.DRAFT).orElseThrow(DriverMarketplaceService::notFound);
        if (shipment.isAssigned()) throw MarketplaceErrors.alreadyAssigned();
        unavailableReason(shipment).ifPresent(why -> { throw MarketplaceErrors.notAvailable(why); });

        Vehicle vehicle = vehicles.findByIdAndDriverId(request.vehicleId(), driver.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy xe của bạn"));
        List<String> issues = eligibility.reasons(shipment, vehicle, pickupDate(shipment));
        if (!issues.isEmpty()) throw MarketplaceErrors.vehicleNotEligible(issues);

        List<DriverShipmentApplication> previous = applications.findByDriverIdAndShipmentIdOrderByAppliedAtDesc(driver.getId(), shipmentId);
        if (previous.stream().anyMatch(a -> a.getStatus().isActive()))
            throw BusinessRuleException.conflict(MarketplaceErrors.ALREADY_APPLIED, "Bạn đã gửi yêu cầu nhận đơn này");
        if (previous.stream().anyMatch(a -> a.getStatus() == ApplicationStatus.REJECTED))
            throw BusinessRuleException.conflict(MarketplaceErrors.APPLICATION_REJECTED_BEFORE, "Khách hàng đã từ chối yêu cầu của bạn cho đơn này");
        if (applications.countByDriverIdAndStatus(driver.getId(), ApplicationStatus.PENDING) >= properties.maxPendingPerDriver())
            throw BusinessRuleException.conflict(MarketplaceErrors.APPLICATION_LIMIT_REACHED,
                    "Bạn đang có " + properties.maxPendingPerDriver() + " yêu cầu chờ phản hồi, vui lòng chờ hoặc rút bớt yêu cầu");

        String message = request.driverMessage() == null || request.driverMessage().isBlank() ? null : request.driverMessage().trim();
        DriverShipmentApplication application;
        try {
            application = applications.saveAndFlush(new DriverShipmentApplication(shipmentId, driver.getId(), vehicle.getId(), message, clock.instant()));
        } catch (DataIntegrityViolationException ex) {
            // Two requests from the same driver at once: the partial unique index lets only one through.
            throw BusinessRuleException.conflict(MarketplaceErrors.ALREADY_APPLIED, "Bạn đã gửi yêu cầu nhận đơn này");
        }
        String driverName = users.findById(userId).map(User::getFullName).orElse("Tài xế");
        notifications.notify(shipment.getCustomerId(), NotificationType.APPLICATION_RECEIVED, "Có tài xế muốn nhận đơn " + shipment.getShipmentCode(),
                driverName + " đã gửi yêu cầu nhận đơn " + shipment.getShipmentCode() + " với xe " + vehicle.getPlateNumber() + ".", "SHIPMENT", shipmentId);
        log.info("Driver application created application={} shipment={} driver={}", application.getId(), shipmentId, driver.getId());
        return response(application, shipment, vehicle, driver.getId());
    }

    @Transactional
    public DriverApplicationResponse withdraw(UUID userId, UUID applicationId, String reason) {
        DriverProfile driver = drivers.findByUserId(userId).orElseThrow(DriverMarketplaceService::applicationNotFound);
        DriverShipmentApplication application = applications.findByIdForUpdate(applicationId)
                .filter(a -> a.getDriverId().equals(driver.getId())).orElseThrow(DriverMarketplaceService::applicationNotFound);
        application.withdraw(clock.instant(), reason == null || reason.isBlank() ? null : reason.trim());
        applications.flush();
        Shipment shipment = shipments.findById(application.getShipmentId()).orElseThrow(DriverMarketplaceService::notFound);
        notifications.notify(shipment.getCustomerId(), NotificationType.APPLICATION_WITHDRAWN, "Tài xế đã rút yêu cầu nhận đơn " + shipment.getShipmentCode(),
                "Một tài xế đã rút yêu cầu nhận đơn " + shipment.getShipmentCode() + ".", "SHIPMENT", shipment.getId());
        log.info("Driver application withdrawn application={}", applicationId);
        return response(application, shipment, vehicles.findById(application.getVehicleId()).orElse(null), driver.getId());
    }

    // ------------------------------------------------------------------ my applications

    @Transactional(readOnly = true)
    public PageResponse<DriverApplicationResponse> myApplications(UUID userId, ApplicationStatus status, int page, int size) {
        Optional<DriverProfile> driver = drivers.findByUserId(userId);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50), Sort.by(Sort.Direction.DESC, "appliedAt"));
        if (driver.isEmpty()) return new PageResponse<>(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0);
        UUID driverId = driver.get().getId();
        var rows = status == null ? applications.findByDriverId(driverId, pageable) : applications.findByDriverIdAndStatus(driverId, status, pageable);
        Map<UUID, Shipment> shipmentsById = views.shipments(rows.getContent().stream().map(DriverShipmentApplication::getShipmentId).toList());
        Map<UUID, Vehicle> vehiclesById = views.vehicles(rows.getContent().stream().map(DriverShipmentApplication::getVehicleId).toList());
        return PageResponse.of(rows, a -> response(a, shipmentsById.get(a.getShipmentId()), vehiclesById.get(a.getVehicleId()), driverId));
    }

    @Transactional(readOnly = true)
    public DriverApplicationResponse myApplication(UUID userId, UUID applicationId) {
        DriverProfile driver = drivers.findByUserId(userId).orElseThrow(DriverMarketplaceService::applicationNotFound);
        DriverShipmentApplication application = applications.findById(applicationId)
                .filter(a -> a.getDriverId().equals(driver.getId())).orElseThrow(DriverMarketplaceService::applicationNotFound);
        return response(application, shipments.findById(application.getShipmentId()).orElse(null), vehicles.findById(application.getVehicleId()).orElse(null), driver.getId());
    }

    // ------------------------------------------------------------------ helpers

    /** Why an order is not open for applications, or empty when it is. */
    Optional<String> unavailableReason(Shipment shipment) {
        if (shipment.getStatus() != ShipmentStatus.PUBLISHED) return Optional.of(shipment.isAssigned() ? "Đơn đã có tài xế nhận" : "Đơn không còn nhận tài xế");
        if (shipment.isAssigned()) return Optional.of("Đơn đã có tài xế nhận");
        if (shipment.getVehicleCount() != 1) return Optional.of("Đơn cần nhiều xe chưa hỗ trợ nhận qua Marketplace");
        if (views.acceptedQuote(shipment.getId()).isEmpty()) return Optional.of("Đơn chưa có giá được khách hàng xác nhận");
        Instant deadline = deadline(shipment);
        if (deadline == null || !clock.instant().isBefore(deadline)) return Optional.of("Đã hết hạn nhận đơn");
        return Optional.empty();
    }

    private DriverProfile requireEligibleDriver(UUID userId) {
        DriverProfile driver = drivers.findByUserId(userId).orElseThrow(() -> MarketplaceErrors.driverNotEligible("Bạn chưa có hồ sơ tài xế"));
        if (!driver.isApproved()) throw MarketplaceErrors.driverNotEligible("Hồ sơ tài xế chưa được duyệt");
        return driver;
    }

    private Instant deadline(Shipment shipment) {
        return shipment.getPickupTime() == null ? null : shipment.getPickupTime().minus(properties.applicationCutoff());
    }

    private LocalDate pickupDate(Shipment shipment) {
        return shipment.getPickupTime() == null ? null : shipment.getPickupTime().atZone(shipmentProperties.businessZone()).toLocalDate();
    }

    private DriverApplicationResponse response(DriverShipmentApplication a, Shipment shipment, Vehicle vehicle, UUID driverId) {
        boolean assignedToMe = shipment != null && driverId.equals(shipment.getAssignedDriverId()) && a.getStatus() == ApplicationStatus.ACCEPTED;
        return new DriverApplicationResponse(a.getId(), a.getStatus(), shipment == null ? null : views.shipment(shipment), MarketplaceViews.vehicle(vehicle),
                a.getDriverMessage(), a.getResponseReason(), a.getAppliedAt(), a.getRespondedAt(), a.isPending(),
                shipment == null ? List.of() : MarketplaceViews.stops(shipment, assignedToMe));
    }

    private static MyApplicationBrief brief(DriverShipmentApplication a, Vehicle v) {
        return new MyApplicationBrief(a.getId(), a.getStatus(), a.getVehicleId(), v == null ? null : v.getPlateNumber(), a.getDriverMessage(), a.getResponseReason(),
                a.getAppliedAt(), a.getRespondedAt());
    }

    /** When a driver has several applications for one order (e.g. withdrawn then re-applied) show the active one. */
    private static ApplicationStatus moreRelevant(ApplicationStatus a, ApplicationStatus b) {
        Comparator<ApplicationStatus> rank = Comparator.comparing(s -> s.isActive() ? 0 : 1);
        return rank.compare(a, b) <= 0 ? a : b;
    }

    private static ResourceNotFoundException notFound() { return new ResourceNotFoundException("Không tìm thấy đơn vận chuyển"); }

    private static ResourceNotFoundException applicationNotFound() { return new ResourceNotFoundException("Không tìm thấy yêu cầu nhận đơn"); }

}
