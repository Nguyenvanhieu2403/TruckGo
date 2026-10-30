package com.truckgo.vehicle.dto;

import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.vehicle.Vehicle;
import com.truckgo.vehicle.VehicleType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record VehicleResponse(UUID id, UUID driverId, String plateNumber, VehicleType vehicleType, int maxPayloadKg, int lengthCm, int widthCm, int heightCm,
                              LocalDate registrationExpiryDate, VerificationStatus verificationStatus, String rejectionReason,
                              Instant submittedAt, Instant reviewedAt, Instant createdAt, Instant updatedAt) {
    public static VehicleResponse from(Vehicle v) {
        return new VehicleResponse(v.getId(), v.getDriverId(), v.getPlateNumber(), v.getVehicleType(), v.getMaxPayloadKg(), v.getLengthCm(), v.getWidthCm(), v.getHeightCm(),
                v.getRegistrationExpiryDate(), v.getVerificationStatus(), v.getRejectionReason(), v.getSubmittedAt(), v.getReviewedAt(), v.getCreatedAt(), v.getUpdatedAt());
    }
}
