package com.truckgo.marketplace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.util.UUID;

/**
 * One driver with one vehicle on an order; an order needing N vehicles has up to N active assignments (slots 1..N).
 * Rows are inserted by {@link AssignmentService} with SQL because the busy period is a PostgreSQL range guarded by
 * exclusion constraints; this entity reads them and records release/finish.
 */
@Entity
@Table(name = "shipment_assignments")
public class ShipmentAssignment {
    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "driver_id", nullable = false, updatable = false) private UUID driverId;
    @Column(name = "vehicle_id", nullable = false, updatable = false) private UUID vehicleId;
    @Column(name = "application_id", updatable = false) private UUID applicationId;
    @Column(name = "slot_no", nullable = false, updatable = false) private int slotNo;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AssignmentStatus status;
    @Formula("lower(busy_period)") private Instant busyFrom;
    @Formula("upper(busy_period)") private Instant busyUntil;
    @Column(name = "assigned_at", nullable = false, updatable = false) private Instant assignedAt;
    @Column(name = "assigned_by", nullable = false, updatable = false) private UUID assignedBy;
    @Column(name = "released_at") private Instant releasedAt;
    @Column(name = "released_by") private UUID releasedBy;
    @Column(name = "release_reason") private String releaseReason;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected ShipmentAssignment() { }

    /** releasedBy is null when a system task releases it (order expired). */
    void release(Instant at, UUID by, String reason) {
        status = AssignmentStatus.RELEASED;
        releasedAt = at;
        releasedBy = by;
        releaseReason = reason;
        updatedAt = at;
    }

    void finish(Instant at) {
        status = AssignmentStatus.FINISHED;
        finishedAt = at;
        updatedAt = at;
    }

    public boolean isActive() { return status == AssignmentStatus.ACTIVE; }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID getDriverId() { return driverId; }
    public UUID getVehicleId() { return vehicleId; }
    public UUID getApplicationId() { return applicationId; }
    public int getSlotNo() { return slotNo; }
    public AssignmentStatus getStatus() { return status; }
    public Instant getBusyFrom() { return busyFrom; }
    public Instant getBusyUntil() { return busyUntil; }
    public Instant getAssignedAt() { return assignedAt; }
    public UUID getAssignedBy() { return assignedBy; }
    public Instant getReleasedAt() { return releasedAt; }
    public UUID getReleasedBy() { return releasedBy; }
    public String getReleaseReason() { return releaseReason; }
    public Instant getFinishedAt() { return finishedAt; }
}
