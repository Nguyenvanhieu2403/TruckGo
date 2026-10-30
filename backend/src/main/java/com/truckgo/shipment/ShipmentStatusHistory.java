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

/** Append-only audit of status changes; immutable once written. */
@Entity
@Immutable
@Table(name = "shipment_status_histories")
public class ShipmentStatusHistory {
    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false) private UUID shipmentId;
    @Enumerated(EnumType.STRING) @Column(name = "old_status") private ShipmentStatus oldStatus;
    @Enumerated(EnumType.STRING) @Column(name = "new_status", nullable = false) private ShipmentStatus newStatus;
    @Column(name = "changed_by", nullable = false) private UUID changedBy;
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

    public UUID getId() { return id; }
    public ShipmentStatus getOldStatus() { return oldStatus; }
    public ShipmentStatus getNewStatus() { return newStatus; }
    public UUID getChangedBy() { return changedBy; }
    public String getChangeReason() { return changeReason; }
    public Instant getCreatedAt() { return createdAt; }
}
