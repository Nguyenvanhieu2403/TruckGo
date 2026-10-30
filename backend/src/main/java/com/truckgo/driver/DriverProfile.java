package com.truckgo.driver;

import com.truckgo.common.domain.VerificationStatus;
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

@Entity
@Table(name = "driver_profiles")
public class DriverProfile {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
    @Column(name = "license_number") private String licenseNumber;
    @Column(name = "license_class") private String licenseClass;
    @Enumerated(EnumType.STRING) @Column(name = "verification_status", nullable = false) private VerificationStatus verificationStatus;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "reviewed_by") private UUID reviewedBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected DriverProfile() { }

    public DriverProfile(UUID userId, String licenseNumber, String licenseClass) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.licenseNumber = licenseNumber;
        this.licenseClass = licenseClass;
        this.verificationStatus = VerificationStatus.NOT_SUBMITTED;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    /** Applies driver-editable fields. An approved profile whose licence data changes goes back to PENDING_REVIEW. */
    public void updateLicense(String number, String licenseClass) {
        boolean changed = !number.equals(this.licenseNumber) || !licenseClass.equals(this.licenseClass);
        this.licenseNumber = number;
        this.licenseClass = licenseClass;
        if (changed && verificationStatus == VerificationStatus.APPROVED) markSubmitted();
    }

    public void markSubmitted() {
        verificationStatus = VerificationStatus.PENDING_REVIEW;
        submittedAt = Instant.now();
        rejectionReason = null;
        reviewedAt = null;
        reviewedBy = null;
    }

    public void review(VerificationStatus decision, UUID adminId, String reason) {
        verificationStatus = decision;
        rejectionReason = reason;
        reviewedAt = Instant.now();
        reviewedBy = adminId;
    }

    public boolean isApproved() { return verificationStatus == VerificationStatus.APPROVED; }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getLicenseNumber() { return licenseNumber; }
    public String getLicenseClass() { return licenseClass; }
    public VerificationStatus getVerificationStatus() { return verificationStatus; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public UUID getReviewedBy() { return reviewedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
