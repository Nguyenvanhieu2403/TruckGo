package com.truckgo.marketplace;

import com.truckgo.admin.AdminAuditService;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import com.truckgo.driver.DriverProfile;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminApplicationResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminAssignmentResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminMarketplaceShipment;
import com.truckgo.marketplace.dto.MarketplaceDtos.AssignmentOverview;
import com.truckgo.marketplace.dto.MarketplaceDtos.ScheduleEntry;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.user.User;
import com.truckgo.vehicle.Vehicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Marketplace monitoring for administrators (orders still missing drivers, applications, assignment history, driver
 * and vehicle schedules) and the exceptional cases: removing or replacing a driver before the trip starts. Every
 * change is also written to admin_audit_logs.
 */
@Service
public class AdminMarketplaceService {
    private final MarketplaceQuery query;
    private final DriverShipmentApplicationRepository applications;
    private final ShipmentAssignmentHistoryRepository assignments;
    private final MarketplaceViews views;
    private final AssignmentViews assignmentViews;
    private final AssignmentService assignmentService;
    private final ShipmentRepository shipments;
    private final AdminAuditService audit;
    private final Clock clock;

    public AdminMarketplaceService(MarketplaceQuery query, DriverShipmentApplicationRepository applications, ShipmentAssignmentHistoryRepository assignments,
                                   MarketplaceViews views, AssignmentViews assignmentViews, AssignmentService assignmentService, ShipmentRepository shipments,
                                   AdminAuditService audit, Clock clock) {
        this.query = query;
        this.applications = applications;
        this.assignments = assignments;
        this.views = views;
        this.assignmentViews = assignmentViews;
        this.assignmentService = assignmentService;
        this.shipments = shipments;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminMarketplaceShipment> shipments(ShipmentStatus status, String keyword, boolean understaffed, int page, int size) {
        return query.adminShipments(status, keyword, understaffed, page, size);
    }

    @Transactional(readOnly = true)
    public AssignmentOverview shipmentAssignments(UUID shipmentId) {
        return assignmentViews.overview(shipments.findById(shipmentId).orElseThrow(AdminMarketplaceService::shipmentNotFound));
    }

    @Transactional
    public AssignmentOverview release(UUID adminId, UUID shipmentId, UUID assignmentId, String reason) {
        Shipment shipment = shipments.findByIdForUpdate(shipmentId).orElseThrow(AdminMarketplaceService::shipmentNotFound);
        ShipmentAssignment released = assignmentService.release(shipment, assignmentId, AssignmentService.Actor.user(adminId), reason.trim());
        audit.record(adminId, "ASSIGNMENT_RELEASED", "SHIPMENT", shipmentId, "assignment=" + released.getId() + " driver=" + released.getDriverId() + " reason=" + reason.trim());
        return assignmentViews.overview(shipment);
    }

    @Transactional
    public AssignmentOverview replace(UUID adminId, UUID shipmentId, UUID assignmentId, UUID applicationId, String reason) {
        Shipment shipment = shipments.findByIdForUpdate(shipmentId).orElseThrow(AdminMarketplaceService::shipmentNotFound);
        DriverShipmentApplication application = applications.findByIdForUpdate(applicationId).filter(a -> a.getShipmentId().equals(shipmentId))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu nhận đơn"));
        if (shipment.getPickupTime() == null || !clock.instant().isBefore(shipment.getPickupTime()))
            throw MarketplaceErrors.notAvailable("Đã quá giờ lấy hàng, không thể chọn tài xế");
        if (views.acceptedQuote(shipmentId).isEmpty()) throw MarketplaceErrors.notAvailable("Đơn chưa có báo giá được xác nhận, không thể chọn tài xế");
        var outcome = assignmentService.replace(shipment, assignmentId, application, AssignmentService.Actor.user(adminId), reason.trim());
        audit.record(adminId, "ASSIGNMENT_REPLACED", "SHIPMENT", shipmentId, "old=" + assignmentId + " new=" + outcome.assignment().getId() + " reason=" + reason.trim());
        return assignmentViews.overview(shipment);
    }

    @Transactional(readOnly = true)
    public PageResponse<ScheduleEntry> schedules(UUID driverId, UUID vehicleId, Instant from, Instant to, int page, int size) {
        Instant start = from != null ? from : clock.instant().minus(java.time.Duration.ofDays(1));
        Instant end = to != null && to.isAfter(start) ? to : start.plus(java.time.Duration.ofDays(30));
        return assignmentViews.schedules(driverId, vehicleId, start, end, page, size);
    }

    private static ResourceNotFoundException shipmentNotFound() { return new ResourceNotFoundException("Không tìm thấy đơn vận chuyển"); }

    @Transactional(readOnly = true)
    public PageResponse<AdminApplicationResponse> applications(ApplicationStatus status, UUID shipmentId, UUID driverId, int page, int size) {
        Specification<DriverShipmentApplication> spec = (root, q, cb) -> cb.conjunction();
        if (status != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        if (shipmentId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("shipmentId"), shipmentId));
        if (driverId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("driverId"), driverId));
        Page<DriverShipmentApplication> rows = applications.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "appliedAt")));
        List<DriverShipmentApplication> content = rows.getContent();
        Map<UUID, Shipment> shipments = views.shipments(content.stream().map(DriverShipmentApplication::getShipmentId).toList());
        Map<UUID, Vehicle> vehicles = views.vehicles(content.stream().map(DriverShipmentApplication::getVehicleId).toList());
        Map<UUID, DriverProfile> drivers = views.drivers(content.stream().map(DriverShipmentApplication::getDriverId).toList());
        Map<UUID, User> users = views.driverUsers(drivers.values());
        return PageResponse.of(rows, a -> admin(a, shipments.get(a.getShipmentId()), users.get(a.getDriverId()), vehicles.get(a.getVehicleId())));
    }

    @Transactional(readOnly = true)
    public AdminApplicationResponse application(UUID id) {
        DriverShipmentApplication a = applications.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu nhận đơn"));
        Map<UUID, DriverProfile> drivers = views.drivers(List.of(a.getDriverId()));
        return admin(a, views.shipments(List.of(a.getShipmentId())).get(a.getShipmentId()), views.driverUsers(drivers.values()).get(a.getDriverId()),
                views.vehicles(List.of(a.getVehicleId())).get(a.getVehicleId()));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminAssignmentResponse> assignments(int page, int size) {
        Page<ShipmentAssignmentHistory> rows = assignments.findAll(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<AdminAssignmentResponse> mapped = assignmentViews.history(rows.getContent());
        return new PageResponse<>(mapped, rows.getNumber(), rows.getSize(), rows.getTotalElements(), rows.getTotalPages());
    }

    private static AdminApplicationResponse admin(DriverShipmentApplication a, Shipment s, User driver, Vehicle v) {
        return new AdminApplicationResponse(a.getId(), a.getStatus(), a.getShipmentId(), s == null ? null : s.getShipmentCode(), s == null ? null : s.getStatus(),
                a.getDriverId(), driver == null ? null : driver.getFullName(), driver == null ? null : driver.getPhone(), a.getVehicleId(),
                v == null ? null : v.getPlateNumber(), a.getDriverMessage(), a.getResponseReason(), a.getAppliedAt(), a.getRespondedAt());
    }
}
