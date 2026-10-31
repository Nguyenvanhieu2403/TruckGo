package com.truckgo.marketplace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** Append-only record of who was assigned to an order, by whom and from which application. */
@Entity
@Immutable
@Table(name = "shipment_assignment_histories")
public class ShipmentAssignmentHistory {
    public enum Action { ASSIGNED, UNASSIGNED }

    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false) private UUID shipmentId;
    @Column(name = "previous_driver_id") private UUID previousDriverId;
    @Column(name = "new_driver_id") private UUID newDriverId;
    @Column(name = "vehicle_id") private UUID vehicleId;
    @Column(name = "application_id") private UUID applicationId;
    @Column(nullable = false) private String action;
    @Column private String reason;
    @Column(name = "performed_by", nullable = false) private UUID performedBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ShipmentAssignmentHistory() { }

    public ShipmentAssignmentHistory(UUID shipmentId, UUID previousDriverId, UUID newDriverId, UUID vehicleId, UUID applicationId, Action action,
                                     String reason, UUID performedBy, Instant at) {
        this.id = UUID.randomUUID();
        this.shipmentId = shipmentId;
        this.previousDriverId = previousDriverId;
        this.newDriverId = newDriverId;
        this.vehicleId = vehicleId;
        this.applicationId = applicationId;
        this.action = action.name();
        this.reason = reason;
        this.performedBy = performedBy;
        this.createdAt = at;
    }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID getPreviousDriverId() { return previousDriverId; }
    public UUID getNewDriverId() { return newDriverId; }
    public UUID getVehicleId() { return vehicleId; }
    public UUID getApplicationId() { return applicationId; }
    public String getAction() { return action; }
    public String getReason() { return reason; }
    public UUID getPerformedBy() { return performedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
