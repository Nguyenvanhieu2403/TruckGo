package com.truckgo.shipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Assigned driver and vehicle of an order, supplied by the marketplace module so the shipment module does not depend
 * on it (same seam as {@link ShipmentPriceProvider}).
 */
public interface ShipmentAssignmentProvider {
    record AssignedDriver(UUID driverId, String fullName, String phone, UUID vehicleId, String plateNumber, String vehicleType,
                          Integer maxPayloadKg, Instant assignedAt, UUID applicationId) { }

    record ApplicationStats(long pending, long total) { }

    Optional<AssignedDriver> assignment(Shipment shipment);

    ApplicationStats applicationStats(UUID shipmentId);
}
