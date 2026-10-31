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

/** The OTP challenge for one delivery. Only the HMAC of the code is kept. */
@Entity
@Table(name = "shipment_delivery_confirmations")
public class DeliveryConfirmation {
    public enum Status { PENDING, CONFIRMED, LOCKED, SUPERSEDED }

    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "otp_hash", nullable = false) private String otpHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "failed_attempts", nullable = false) private int failedAttempts;
    @Column(name = "max_attempts", nullable = false, updatable = false) private int maxAttempts;
    @Column(name = "send_count", nullable = false) private int sendCount;
    @Column(name = "last_sent_at", nullable = false) private Instant lastSentAt;
    @Column(name = "confirmed_at") private Instant confirmedAt;
    @Column(name = "confirmed_by") private UUID confirmedBy;
    @Column private String note;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected DeliveryConfirmation() { }

    public DeliveryConfirmation(UUID shipmentId, String otpHash, Instant expiresAt, int maxAttempts, Instant sentAt) {
        this.id = UUID.randomUUID();
        this.shipmentId = shipmentId;
        this.otpHash = otpHash;
        this.status = Status.PENDING;
        this.expiresAt = expiresAt;
        this.maxAttempts = maxAttempts;
        this.sendCount = 1;
        this.lastSentAt = sentAt;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    /** Sets the hash of the first code (it is bound to this row's id, so it is computed after construction). */
    void reissueInitial(String otpHash) { this.otpHash = otpHash; }

    /** A fresh code replaces the old one; attempts start over but the send counter keeps growing. */
    void reissue(String otpHash, Instant expiresAt, Instant sentAt) {
        this.otpHash = otpHash;
        this.expiresAt = expiresAt;
        this.failedAttempts = 0;
        this.sendCount++;
        this.lastSentAt = sentAt;
        this.status = Status.PENDING;
    }

    /** Returns true when this failure used up the last attempt. */
    boolean fail() {
        failedAttempts++;
        if (failedAttempts >= maxAttempts) { status = Status.LOCKED; return true; }
        return false;
    }

    void confirm(Instant at, UUID by, String note) {
        status = Status.CONFIRMED;
        confirmedAt = at;
        confirmedBy = by;
        this.note = note;
    }

    void supersede() { if (status == Status.PENDING || status == Status.LOCKED) status = Status.SUPERSEDED; }

    boolean isExpiredAt(Instant at) { return !expiresAt.isAfter(at); }
    int attemptsLeft() { return Math.max(maxAttempts - failedAttempts, 0); }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public String getOtpHash() { return otpHash; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getFailedAttempts() { return failedAttempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public int getSendCount() { return sendCount; }
    public Instant getLastSentAt() { return lastSentAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public String getNote() { return note; }
}
