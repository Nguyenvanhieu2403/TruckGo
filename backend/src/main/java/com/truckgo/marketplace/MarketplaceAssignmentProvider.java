package com.truckgo.marketplace;

import com.truckgo.driver.DriverProfile;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentAssignmentProvider;
import com.truckgo.user.User;
import com.truckgo.vehicle.Vehicle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Gives the shipment detail its assigned drivers and application counts. */
@Component
class MarketplaceAssignmentProvider implements ShipmentAssignmentProvider {
    private final MarketplaceViews views;
    private final DriverShipmentApplicationRepository applications;
    private final ShipmentAssignmentRepository assignments;

    MarketplaceAssignmentProvider(MarketplaceViews views, DriverShipmentApplicationRepository applications, ShipmentAssignmentRepository assignments) {
        this.views = views;
        this.applications = applications;
        this.assignments = assignments;
    }

    @Override
    public Optional<AssignedDriver> assignment(Shipment shipment) {
        if (!shipment.isAssigned()) return Optional.empty();
        Optional<AssignedDriver> lead = assignments(shipment).stream().filter(AssignedDriver::lead).findFirst();
        if (lead.isPresent()) return lead;
        // Orders whose assignment row is missing (should not happen after V9): fall back to the order's own columns.
        DriverProfile driver = views.drivers(List.of(shipment.getAssignedDriverId())).get(shipment.getAssignedDriverId());
        User user = driver == null ? null : views.driverUsers(List.of(driver)).get(driver.getId());
        Vehicle vehicle = views.vehicles(List.of(shipment.getAssignedVehicleId())).get(shipment.getAssignedVehicleId());
        UUID applicationId = applications.findFirstByShipmentIdAndStatus(shipment.getId(), ApplicationStatus.ACCEPTED).map(DriverShipmentApplication::getId).orElse(null);
        return Optional.of(new AssignedDriver(shipment.getAssignedDriverId(), user == null ? null : user.getFullName(), user == null ? null : user.getPhone(),
                shipment.getAssignedVehicleId(), vehicle == null ? null : vehicle.getPlateNumber(), vehicle == null ? null : vehicle.getVehicleType().name(),
                vehicle == null ? null : vehicle.getMaxPayloadKg(), shipment.getAssignedAt(), applicationId, null, 1, true,
                vehicle == null ? null : vehicle.getBodyTypeCode()));
    }

    @Override
    public List<AssignedDriver> assignments(Shipment shipment) {
        List<ShipmentAssignment> rows = assignments.findByShipmentIdAndStatusOrderBySlotNoAsc(shipment.getId(), AssignmentStatus.ACTIVE);
        Map<UUID, DriverProfile> drivers = views.drivers(rows.stream().map(ShipmentAssignment::getDriverId).toList());
        Map<UUID, User> users = views.driverUsers(drivers.values());
        Map<UUID, Vehicle> vehicles = views.vehicles(rows.stream().map(ShipmentAssignment::getVehicleId).toList());
        return rows.stream().map(a -> {
            User user = users.get(a.getDriverId());
            Vehicle v = vehicles.get(a.getVehicleId());
            return new AssignedDriver(a.getDriverId(), user == null ? null : user.getFullName(), user == null ? null : user.getPhone(), a.getVehicleId(),
                    v == null ? null : v.getPlateNumber(), v == null ? null : v.getVehicleType().name(), v == null ? null : v.getMaxPayloadKg(),
                    a.getAssignedAt(), a.getApplicationId(), a.getId(), a.getSlotNo(), a.getDriverId().equals(shipment.getAssignedDriverId()),
                    v == null ? null : v.getBodyTypeCode());
        }).toList();
    }

    @Override
    public ApplicationStats applicationStats(UUID shipmentId) {
        return new ApplicationStats(applications.countByShipmentIdAndStatus(shipmentId, ApplicationStatus.PENDING), applications.countByShipmentId(shipmentId));
    }
}
