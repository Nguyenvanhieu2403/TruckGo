package com.truckgo.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** An object that must be removed from storage, retried until it is. */
@Entity
@Table(name = "storage_deletion_tasks")
public class StorageDeletionTask {
    public enum Reason { ATTACHMENT_DELETED, DRAFT_DELETED, SHIPMENT_CANCELLED, UPLOAD_COMPENSATION, ORPHAN }
    public enum Status { PENDING, DONE, SKIPPED, FAILED }

    @Id private UUID id;
    @Column(name = "file_key", nullable = false, updatable = false) private String fileKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) private Reason reason;
    @Column(name = "shipment_id", updatable = false) private UUID shipmentId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "last_error") private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;

    protected StorageDeletionTask() { }

    public StorageDeletionTask(String fileKey, Reason reason, UUID shipmentId, Instant now) {
        this.id = UUID.randomUUID();
        this.fileKey = fileKey;
        this.reason = reason;
        this.shipmentId = shipmentId;
        this.status = Status.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    void done(Instant at) { status = Status.DONE; completedAt = at; lastError = null; }

    void skip(Instant at, String why) { status = Status.SKIPPED; completedAt = at; lastError = why; }

    /** Exponential backoff (1 min, 2, 4 … capped at maxDelay); gives up after maxAttempts. */
    void failed(Instant at, String error, int maxAttempts, java.time.Duration maxDelay) {
        attempts++;
        lastError = error == null ? null : error.length() > 300 ? error.substring(0, 300) : error;
        if (attempts >= maxAttempts) { status = Status.FAILED; completedAt = at; return; }
        long minutes = 1L << Math.min(attempts - 1, 20);
        java.time.Duration delay = java.time.Duration.ofMinutes(minutes);
        nextAttemptAt = at.plus(delay.compareTo(maxDelay) > 0 ? maxDelay : delay);
    }

    /** Reserves the task for one worker for a while, so concurrent instances do not process it twice. */
    void lease(Instant until) { nextAttemptAt = until; }

    public UUID getId() { return id; }
    public String getFileKey() { return fileKey; }
    public Reason getReason() { return reason; }
    public UUID getShipmentId() { return shipmentId; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getCompletedAt() { return completedAt; }
}
