package com.truckgo.shipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Metadata only; the bytes live in object storage under a server-generated {@code fileKey}. */
@Entity
@Table(name = "shipment_attachments")
public class ShipmentAttachment {
    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "file_name", nullable = false, updatable = false) private String fileName;
    @Column(name = "file_key", nullable = false, updatable = false) private String fileKey;
    @Column(name = "content_type", nullable = false, updatable = false) private String contentType;
    @Column(name = "file_size", nullable = false, updatable = false) private long fileSize;
    @Enumerated(EnumType.STRING) @Column(name = "file_type", nullable = false, updatable = false) private AttachmentFileType fileType;
    @Column(name = "uploaded_by", nullable = false, updatable = false) private UUID uploadedBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ShipmentAttachment() { }

    public ShipmentAttachment(UUID id, UUID shipmentId, String fileName, String fileKey, String contentType, long fileSize, AttachmentFileType fileType, UUID uploadedBy) {
        this.id = id;
        this.shipmentId = shipmentId;
        this.fileName = fileName;
        this.fileKey = fileKey;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.fileType = fileType;
        this.uploadedBy = uploadedBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public String getFileName() { return fileName; }
    public String getFileKey() { return fileKey; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public AttachmentFileType getFileType() { return fileType; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
