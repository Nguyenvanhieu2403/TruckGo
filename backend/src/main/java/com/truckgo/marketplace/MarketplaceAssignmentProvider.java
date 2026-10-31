package com.truckgo.marketplace;

import com.truckgo.driver.DriverProfile;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentAssignmentProvider;
import com.truckgo.user.User;
import com.truckgo.vehicle.Vehicle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Gives the shipment detail its assigned driver and application counts. */
@Component
class MarketplaceAssignmentProvider implements ShipmentAssignmentProvider {
    private final MarketplaceViews views;
    private final DriverShipmentApplicationRepository applications;

    MarketplaceAssignmentProvider(MarketplaceViews views, DriverShipmentApplicationRepository applications) {
        this.views = views;
        this.applications = applications;
    }

    @Override
    public Optional<AssignedDriver> assignment(Shipment shipment) {
        if (!shipment.isAssigned()) return Optional.empty();
        DriverProfile driver = views.drivers(List.of(shipment.getAssignedDriverId())).get(shipment.getAssignedDriverId());
        User user = driver == null ? null : views.driverUsers(List.of(driver)).get(driver.getId());
        Vehicle vehicle = views.vehicles(List.of(shipment.getAssignedVehicleId())).get(shipment.getAssignedVehicleId());
        UUID applicationId = applications.findFirstByShipmentIdAndStatus(shipment.getId(), ApplicationStatus.ACCEPTED).map(DriverShipmentApplication::getId).orElse(null);
        return Optional.of(new AssignedDriver(shipment.getAssignedDriverId(), user == null ? null : user.getFullName(), user == null ? null : user.getPhone(),
                shipment.getAssignedVehicleId(), vehicle == null ? null : vehicle.getPlateNumber(), vehicle == null ? null : vehicle.getVehicleType().name(),
                vehicle == null ? null : vehicle.getMaxPayloadKg(), shipment.getAssignedAt(), applicationId));
    }

    @Override
    public ApplicationStats applicationStats(UUID shipmentId) {
        return new ApplicationStats(applications.countByShipmentIdAndStatus(shipmentId, ApplicationStatus.PENDING), applications.countByShipmentId(shipmentId));
    }
}
