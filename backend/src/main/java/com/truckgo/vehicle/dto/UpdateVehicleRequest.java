package com.truckgo.vehicle.dto;

import com.truckgo.vehicle.VehicleType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Partial update: null fields are left unchanged. Review fields are intentionally absent. */
public record UpdateVehicleRequest(
        @Size(max = 15, message = "Biển số quá dài") String plateNumber,
        VehicleType vehicleType,
        @Size(max = 40) String bodyTypeCode,
        @Positive(message = "Tải trọng phải lớn hơn 0") @Max(value = 60000, message = "Tải trọng tối đa 60.000 kg") Integer maxPayloadKg,
        @Positive(message = "Chiều dài phải lớn hơn 0") @Max(value = 2000, message = "Chiều dài tối đa 2.000 cm") Integer lengthCm,
        @Positive(message = "Chiều rộng phải lớn hơn 0") @Max(value = 400, message = "Chiều rộng tối đa 400 cm") Integer widthCm,
        @Positive(message = "Chiều cao phải lớn hơn 0") @Max(value = 500, message = "Chiều cao tối đa 500 cm") Integer heightCm,
        LocalDate registrationExpiryDate) { }
