package com.truckgo.marketplace;

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

/** A driver's request to carry an order with one of their vehicles. Only a PENDING application can change. */
@Entity
@Table(name = "driver_shipment_applications")
public class DriverShipmentApplication {
    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "driver_id", nullable = false, updatable = false) private UUID driverId;
    @Column(name = "vehicle_id", nullable = false, updatable = false) private UUID vehicleId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ApplicationStatus status;
    @Column(name = "driver_message", updatable = false) private String driverMessage;
    @Column(name = "response_reason") private String responseReason;
    @Column(name = "applied_at", nullable = false, updatable = false) private Instant appliedAt;
    @Column(name = "responded_at") private Instant respondedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected DriverShipmentApplication() { }

    public DriverShipmentApplication(UUID shipmentId, UUID driverId, UUID vehicleId, String driverMessage, Instant at) {
        this.id = UUID.randomUUID();
        this.shipmentId = shipmentId;
        this.driverId = driverId;
        this.vehicleId = vehicleId;
        this.driverMessage = driverMessage;
        this.status = ApplicationStatus.PENDING;
        this.appliedAt = at;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public void accept(Instant at) { respond(ApplicationStatus.ACCEPTED, at, null); }
    public void reject(Instant at, String reason) { respond(ApplicationStatus.REJECTED, at, reason); }
    public void withdraw(Instant at, String reason) { respond(ApplicationStatus.WITHDRAWN, at, reason); }
    public void expire(Instant at, String reason) { respond(ApplicationStatus.EXPIRED, at, reason); }

    private void respond(ApplicationStatus target, Instant at, String reason) {
        if (status != ApplicationStatus.PENDING) throw MarketplaceErrors.notPending();
        status = target;
        respondedAt = at;
        responseReason = reason;
    }

    public boolean isPending() { return status == ApplicationStatus.PENDING; }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID getDriverId() { return driverId; }
    public UUID getVehicleId() { return vehicleId; }
    public ApplicationStatus getStatus() { return status; }
    public String getDriverMessage() { return driverMessage; }
    public String getResponseReason() { return responseReason; }
    public Instant getAppliedAt() { return appliedAt; }
    public Instant getRespondedAt() { return respondedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

}
