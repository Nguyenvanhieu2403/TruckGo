package com.truckgo.marketplace;

import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import com.truckgo.driver.DriverProfile;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminApplicationResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminAssignmentResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminMarketplaceShipment;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.user.User;
import com.truckgo.vehicle.Vehicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only monitoring of the marketplace for administrators. */
@Service
public class AdminMarketplaceService {
    private final MarketplaceQuery query;
    private final DriverShipmentApplicationRepository applications;
    private final ShipmentAssignmentHistoryRepository assignments;
    private final MarketplaceViews views;

    public AdminMarketplaceService(MarketplaceQuery query, DriverShipmentApplicationRepository applications, ShipmentAssignmentHistoryRepository assignments,
                                   MarketplaceViews views) {
        this.query = query;
        this.applications = applications;
        this.assignments = assignments;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminMarketplaceShipment> shipments(ShipmentStatus status, String keyword, int page, int size) {
        return query.adminShipments(status, keyword, page, size);
    }

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
        List<ShipmentAssignmentHistory> content = rows.getContent();
        Map<UUID, Shipment> shipments = views.shipments(content.stream().map(ShipmentAssignmentHistory::getShipmentId).toList());
        Map<UUID, Vehicle> vehicles = views.vehicles(content.stream().map(ShipmentAssignmentHistory::getVehicleId).filter(java.util.Objects::nonNull).toList());
        Map<UUID, DriverProfile> drivers = views.drivers(content.stream().map(ShipmentAssignmentHistory::getNewDriverId).filter(java.util.Objects::nonNull).toList());
        Map<UUID, User> driverUsers = views.driverUsers(drivers.values());
        Map<UUID, User> actors = views.users(content.stream().map(ShipmentAssignmentHistory::getPerformedBy).toList());
        return PageResponse.of(rows, h -> {
            Shipment s = shipments.get(h.getShipmentId());
            User driver = h.getNewDriverId() == null ? null : driverUsers.get(h.getNewDriverId());
            Vehicle v = h.getVehicleId() == null ? null : vehicles.get(h.getVehicleId());
            User actor = actors.get(h.getPerformedBy());
            return new AdminAssignmentResponse(h.getId(), h.getShipmentId(), s == null ? null : s.getShipmentCode(), h.getAction(), h.getNewDriverId(),
                    driver == null ? null : driver.getFullName(), h.getPreviousDriverId(), h.getVehicleId(), v == null ? null : v.getPlateNumber(),
                    h.getApplicationId(), h.getReason(), h.getPerformedBy(), actor == null ? null : actor.getFullName(), h.getCreatedAt());
        });
    }

    private static AdminApplicationResponse admin(DriverShipmentApplication a, Shipment s, User driver, Vehicle v) {
        return new AdminApplicationResponse(a.getId(), a.getStatus(), a.getShipmentId(), s == null ? null : s.getShipmentCode(), s == null ? null : s.getStatus(),
                a.getDriverId(), driver == null ? null : driver.getFullName(), driver == null ? null : driver.getPhone(), a.getVehicleId(),
                v == null ? null : v.getPlateNumber(), a.getDriverMessage(), a.getResponseReason(), a.getAppliedAt(), a.getRespondedAt());
    }
}
