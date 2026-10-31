package com.truckgo.trip;

import com.truckgo.common.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Progress of the assigned driver at one stop: PENDING -> ARRIVED -> COMPLETED. */
@Entity
@Table(name = "shipment_stop_progress")
public class StopProgress {
    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "shipment_stop_id", nullable = false, updatable = false) private UUID stopId;
    @Column(name = "driver_id", nullable = false, updatable = false) private UUID driverId;
    @Column(name = "sequence_no", nullable = false, updatable = false) private int sequenceNo;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private StopStatus status;
    @Column(name = "arrived_at") private Instant arrivedAt;
    @Column(name = "departed_at") private Instant departedAt;
    @Column private String note;
    @Column private BigDecimal latitude;
    @Column private BigDecimal longitude;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected StopProgress() { }

    public StopProgress(UUID shipmentId, UUID stopId, UUID driverId, int sequenceNo) {
        this.id = UUID.randomUUID();
        this.shipmentId = shipmentId;
        this.stopId = stopId;
        this.driverId = driverId;
        this.sequenceNo = sequenceNo;
        this.status = StopStatus.PENDING;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public void arrive(Instant at, String note, GeoFix fix) {
        if (status != StopStatus.PENDING) throw BusinessRuleException.conflict(TripErrors.STOP_OUT_OF_ORDER, "Đã xác nhận đến điểm này");
        status = StopStatus.ARRIVED;
        arrivedAt = at;
        record(note, fix);
    }

    public void complete(Instant at, String note, GeoFix fix) {
        if (status == StopStatus.PENDING) arrive(at, null, fix);
        if (status != StopStatus.ARRIVED) throw BusinessRuleException.conflict(TripErrors.STOP_OUT_OF_ORDER, "Điểm này đã hoàn thành");
        status = StopStatus.COMPLETED;
        departedAt = at;
        record(note, fix);
    }

    private void record(String note, GeoFix fix) {
        if (note != null) this.note = note;
        if (fix != null) { latitude = fix.latitude(); longitude = fix.longitude(); }
    }

    public boolean isDone() { return status == StopStatus.COMPLETED || status == StopStatus.SKIPPED; }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID getStopId() { return stopId; }
    public UUID getDriverId() { return driverId; }
    public int getSequenceNo() { return sequenceNo; }
    public StopStatus getStatus() { return status; }
    public Instant getArrivedAt() { return arrivedAt; }
    public Instant getDepartedAt() { return departedAt; }
    public String getNote() { return note; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }
}
