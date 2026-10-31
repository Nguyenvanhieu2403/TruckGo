package com.truckgo.trip;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One accepted GPS fix; append-only. */
@Entity
@Immutable
@Table(name = "shipment_tracking_locations")
public class TrackingLocation {
    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false) private UUID shipmentId;
    @Column(name = "driver_id", nullable = false) private UUID driverId;
    @Column(nullable = false) private BigDecimal latitude;
    @Column(nullable = false) private BigDecimal longitude;
    @Column private BigDecimal accuracy;
    @Column private BigDecimal speed;
    @Column private BigDecimal heading;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected TrackingLocation() { }

    public TrackingLocation(UUID shipmentId, UUID driverId, GeoFix fix, BigDecimal accuracy, BigDecimal speed, BigDecimal heading,
                            Instant recordedAt, Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.shipmentId = shipmentId;
        this.driverId = driverId;
        this.latitude = fix.latitude();
        this.longitude = fix.longitude();
        this.accuracy = accuracy;
        this.speed = speed;
        this.heading = heading;
        this.recordedAt = recordedAt;
        this.receivedAt = receivedAt;
        this.createdAt = receivedAt;
    }

    public GeoFix fix() { return new GeoFix(latitude, longitude); }
    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID getDriverId() { return driverId; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public BigDecimal getAccuracy() { return accuracy; }
    public BigDecimal getSpeed() { return speed; }
    public BigDecimal getHeading() { return heading; }
    public Instant getRecordedAt() { return recordedAt; }
    public Instant getReceivedAt() { return receivedAt; }
}
