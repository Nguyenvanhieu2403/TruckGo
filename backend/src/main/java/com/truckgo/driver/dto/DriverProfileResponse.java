package com.truckgo.driver.dto;

import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.driver.DriverProfile;

import java.time.Instant;
import java.util.UUID;

public record DriverProfileResponse(UUID id, UUID userId, String licenseNumber, String licenseClass, VerificationStatus verificationStatus,
                                    String rejectionReason, Instant submittedAt, Instant reviewedAt, Instant createdAt, Instant updatedAt) {
    public static DriverProfileResponse from(DriverProfile p) {
        return new DriverProfileResponse(p.getId(), p.getUserId(), p.getLicenseNumber(), p.getLicenseClass(), p.getVerificationStatus(),
                p.getRejectionReason(), p.getSubmittedAt(), p.getReviewedAt(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
