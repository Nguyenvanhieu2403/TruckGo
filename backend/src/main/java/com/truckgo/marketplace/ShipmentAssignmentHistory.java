package com.truckgo.marketplace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of who was assigned to or removed from an order, by whom (a user, or a named system task) and why.
 * REPLACED records one driver taking over another's assignment in a single step.
 */
@Entity
@Immutable
@Table(name = "shipment_assignment_histories")
public class ShipmentAssignmentHistory {
    public enum Action { ASSIGNED, UNASSIGNED, REPLACED }

    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false) private UUID shipmentId;
    @Column(name = "previous_driver_id") private UUID previousDriverId;
    @Column(name = "new_driver_id") private UUID newDriverId;
    @Column(name = "vehicle_id") private UUID vehicleId;
    @Column(name = "application_id") private UUID applicationId;
    @Column(nullable = false) private String action;
    @Column private String reason;
    @Column(name = "assignment_id") private UUID assignmentId;
    @Column(name = "performed_by") private UUID performedBy;
    @Column(name = "actor_type", nullable = false) private String actorType = "USER";
    @Column(name = "system_actor") private String systemActor;
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

    public ShipmentAssignmentHistory withAssignment(UUID assignmentId) { this.assignmentId = assignmentId; return this; }

    /** Performed by a system task (performedBy stays null). */
    public static ShipmentAssignmentHistory system(UUID shipmentId, UUID previousDriverId, UUID vehicleId, UUID applicationId, Action action,
                                                   String reason, String systemActor, Instant at) {
        ShipmentAssignmentHistory h = new ShipmentAssignmentHistory(shipmentId, previousDriverId, null, vehicleId, applicationId, action, reason, null, at);
        h.actorType = "SYSTEM";
        h.systemActor = systemActor;
        return h;
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
    public UUID getAssignmentId() { return assignmentId; }
    public String getActorType() { return actorType; }
    public String getSystemActor() { return systemActor; }
    public Instant getCreatedAt() { return createdAt; }
}
