package com.truckgo.shipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit of status changes; immutable once written. A change is made either by a user (changedBy) or by a
 * named system task (systemActor, e.g. "marketplace-expiry-job"); never both, never a stand-in user.
 */
@Entity
@Immutable
@Table(name = "shipment_status_histories")
public class ShipmentStatusHistory {
    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false) private UUID shipmentId;
    @Enumerated(EnumType.STRING) @Column(name = "old_status") private ShipmentStatus oldStatus;
    @Enumerated(EnumType.STRING) @Column(name = "new_status", nullable = false) private ShipmentStatus newStatus;
    public enum ActorType { USER, SYSTEM }

    @Column(name = "changed_by") private UUID changedBy;
    @Enumerated(EnumType.STRING) @Column(name = "actor_type", nullable = false) private ActorType actorType = ActorType.USER;
    @Column(name = "system_actor") private String systemActor;
    @Column(name = "change_reason") private String changeReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ShipmentStatusHistory() { }

    public ShipmentStatusHistory(UUID shipmentId, ShipmentStatus oldStatus, ShipmentStatus newStatus, UUID changedBy, String changeReason) {
        this.id = UUID.randomUUID();
        this.shipmentId = shipmentId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.changedBy = changedBy;
        this.changeReason = changeReason;
        this.createdAt = Instant.now();
    }

    /** A change made by a scheduled job or other system task, identified by name. */
    public static ShipmentStatusHistory system(UUID shipmentId, ShipmentStatus oldStatus, ShipmentStatus newStatus, String systemActor, String changeReason) {
        ShipmentStatusHistory h = new ShipmentStatusHistory(shipmentId, oldStatus, newStatus, null, changeReason);
        h.actorType = ActorType.SYSTEM;
        h.systemActor = systemActor;
        return h;
    }

    public UUID getId() { return id; }
    public ShipmentStatus getOldStatus() { return oldStatus; }
    public ShipmentStatus getNewStatus() { return newStatus; }
    public UUID getChangedBy() { return changedBy; }
    public ActorType getActorType() { return actorType; }
    public String getSystemActor() { return systemActor; }
    public String getChangeReason() { return changeReason; }
    public Instant getCreatedAt() { return createdAt; }
}
