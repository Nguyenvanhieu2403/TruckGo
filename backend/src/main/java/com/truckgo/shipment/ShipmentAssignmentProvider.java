package com.truckgo.shipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Assigned driver and vehicle of an order, supplied by the marketplace module so the shipment module does not depend
 * on it (same seam as {@link ShipmentPriceProvider}).
 */
public interface ShipmentAssignmentProvider {
    record AssignedDriver(UUID driverId, String fullName, String phone, UUID vehicleId, String plateNumber, String vehicleType,
                          Integer maxPayloadKg, Instant assignedAt, UUID applicationId, UUID assignmentId, int slotNo, boolean lead, String bodyTypeCode) { }

    record ApplicationStats(long pending, long total) { }

    /** The lead assignment (the driver who runs the trip), once every vehicle is assigned. */
    Optional<AssignedDriver> assignment(Shipment shipment);

    /** Every active assignment of the order, by slot (also while it is still missing vehicles). */
    List<AssignedDriver> assignments(Shipment shipment);

    ApplicationStats applicationStats(UUID shipmentId);
}
