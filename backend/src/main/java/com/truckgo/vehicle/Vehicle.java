package com.truckgo.vehicle;

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
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "vehicles")
public class Vehicle {
    @Id private UUID id;
    /** driver_profiles.id of the owner. */
    @Column(name = "driver_id", nullable = false, updatable = false) private UUID driverId;
    @Column(name = "plate_number", nullable = false) private String plateNumber;
    @Enumerated(EnumType.STRING) @Column(name = "vehicle_type", nullable = false) private VehicleType vehicleType;
    /** vehicle_body_types.code; null = not declared (vehicles registered before body types were recorded). */
    @Column(name = "body_type_code") private String bodyTypeCode;
    @Column(name = "max_payload_kg", nullable = false) private Integer maxPayloadKg;
    @Column(name = "length_cm", nullable = false) private Integer lengthCm;
    @Column(name = "width_cm", nullable = false) private Integer widthCm;
    @Column(name = "height_cm", nullable = false) private Integer heightCm;
    @Column(name = "registration_expiry_date", nullable = false) private LocalDate registrationExpiryDate;
    @Enumerated(EnumType.STRING) @Column(name = "verification_status", nullable = false) private VerificationStatus verificationStatus;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "reviewed_by") private UUID reviewedBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected Vehicle() { }

    public Vehicle(UUID driverId, String plateNumber, VehicleType vehicleType, int maxPayloadKg, int lengthCm, int widthCm, int heightCm, LocalDate registrationExpiryDate) {
        this.id = UUID.randomUUID();
        this.driverId = driverId;
        this.plateNumber = plateNumber;
        this.vehicleType = vehicleType;
        this.maxPayloadKg = maxPayloadKg;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.registrationExpiryDate = registrationExpiryDate;
        this.verificationStatus = VerificationStatus.NOT_SUBMITTED;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public void setBodyTypeCode(String bodyTypeCode) { this.bodyTypeCode = bodyTypeCode; }

    /** Applies non-null fields. Any change to an approved vehicle sends it back to PENDING_REVIEW. */
    public void update(String plate, VehicleType type, String bodyType, Integer payload, Integer length, Integer width, Integer height, LocalDate expiry) {
        boolean changed = false;
        if (bodyType != null && !bodyType.equals(bodyTypeCode)) { bodyTypeCode = bodyType; changed = true; }
        if (plate != null && !plate.equals(plateNumber)) { plateNumber = plate; changed = true; }
        if (type != null && type != vehicleType) { vehicleType = type; changed = true; }
        if (payload != null && !payload.equals(maxPayloadKg)) { maxPayloadKg = payload; changed = true; }
        if (length != null && !length.equals(lengthCm)) { lengthCm = length; changed = true; }
        if (width != null && !width.equals(widthCm)) { widthCm = width; changed = true; }
        if (height != null && !height.equals(heightCm)) { heightCm = height; changed = true; }
        if (expiry != null && !Objects.equals(expiry, registrationExpiryDate)) { registrationExpiryDate = expiry; changed = true; }
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

    public UUID getId() { return id; }
    public UUID getDriverId() { return driverId; }
    public String getPlateNumber() { return plateNumber; }
    public VehicleType getVehicleType() { return vehicleType; }
    public String getBodyTypeCode() { return bodyTypeCode; }
    public Integer getMaxPayloadKg() { return maxPayloadKg; }
    public Integer getLengthCm() { return lengthCm; }
    public Integer getWidthCm() { return widthCm; }
    public Integer getHeightCm() { return heightCm; }
    public LocalDate getRegistrationExpiryDate() { return registrationExpiryDate; }
    public VerificationStatus getVerificationStatus() { return verificationStatus; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
