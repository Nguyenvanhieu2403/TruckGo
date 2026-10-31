package com.truckgo.trip;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** Whether the assigned driver is sharing location for an order. Created when the trip starts. */
@Entity
@Table(name = "shipment_tracking_sessions")
public class TrackingSession {
    public enum Status { ACTIVE, PAUSED, ENDED }

    @Id @Column(name = "shipment_id") private UUID shipmentId;
    @Column(name = "driver_id", nullable = false, updatable = false) private UUID driverId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(name = "started_at", nullable = false, updatable = false) private Instant startedAt;
    @Column(name = "paused_at") private Instant pausedAt;
    @Column(name = "ended_at") private Instant endedAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected TrackingSession() { }

    public TrackingSession(UUID shipmentId, UUID driverId, Instant at) {
        this.shipmentId = shipmentId;
        this.driverId = driverId;
        this.status = Status.ACTIVE;
        this.startedAt = at;
    }

    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }

    public void pause(Instant at) { if (status == Status.ACTIVE) { status = Status.PAUSED; pausedAt = at; } }
    public void resume() { if (status == Status.PAUSED) { status = Status.ACTIVE; pausedAt = null; } }
    public void end(Instant at) { if (status != Status.ENDED) { status = Status.ENDED; endedAt = at; } }

    public UUID getShipmentId() { return shipmentId; }
    public UUID getDriverId() { return driverId; }
    public Status getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getPausedAt() { return pausedAt; }
    public Instant getEndedAt() { return endedAt; }
}
