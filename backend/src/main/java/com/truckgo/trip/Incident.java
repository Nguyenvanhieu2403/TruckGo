package com.truckgo.trip;

import com.truckgo.common.exception.ConflictException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** A problem reported during a trip. Reporting never cancels or reassigns the order by itself. */
@Entity
@Table(name = "shipment_incidents")
public class Incident {
    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "reported_by", nullable = false, updatable = false) private UUID reportedBy;
    @Column(name = "driver_id", updatable = false) private UUID driverId;
    @Enumerated(EnumType.STRING) @Column(name = "incident_type", nullable = false, updatable = false) private IncidentType type;
    @Column(nullable = false, updatable = false) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private IncidentStatus status;
    @Column(updatable = false) private BigDecimal latitude;
    @Column(updatable = false) private BigDecimal longitude;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;
    @Column(name = "resolution_note") private String resolutionNote;
    @Column(name = "handled_by") private UUID handledBy;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "shipment_incident_attachments", joinColumns = @JoinColumn(name = "incident_id"))
    @Column(name = "attachment_id")
    private Set<UUID> attachmentIds = new LinkedHashSet<>();

    protected Incident() { }

    public Incident(UUID shipmentId, UUID reportedBy, UUID driverId, IncidentType type, String description, GeoFix fix, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.shipmentId = shipmentId;
        this.reportedBy = reportedBy;
        this.driverId = driverId;
        this.type = type;
        this.description = description;
        this.status = IncidentStatus.OPEN;
        if (fix != null) { latitude = fix.latitude(); longitude = fix.longitude(); }
        this.occurredAt = occurredAt;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public void attach(Set<UUID> ids) { attachmentIds.addAll(ids); }

    public void moveTo(IncidentStatus target, UUID adminId, String note, Instant at) {
        if (!status.canMoveTo(target)) throw new ConflictException("Không thể chuyển sự cố từ " + status + " sang " + target);
        status = target;
        handledBy = adminId;
        if (note != null) resolutionNote = note;
        if (target.isClosed()) resolvedAt = at;
    }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID getReportedBy() { return reportedBy; }
    public UUID getDriverId() { return driverId; }
    public IncidentType getType() { return type; }
    public String getDescription() { return description; }
    public IncidentStatus getStatus() { return status; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getResolutionNote() { return resolutionNote; }
    public UUID getHandledBy() { return handledBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<UUID> getAttachmentIds() { return attachmentIds; }
}
