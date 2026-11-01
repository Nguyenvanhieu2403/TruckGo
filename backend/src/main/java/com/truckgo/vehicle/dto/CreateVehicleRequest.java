package com.truckgo.vehicle.dto;

import com.truckgo.vehicle.VehicleType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** No driverId: the owner is always the authenticated driver. */
public record CreateVehicleRequest(
        @NotBlank(message = "Biển số không được để trống") @Size(max = 15, message = "Biển số quá dài") String plateNumber,
        @NotNull(message = "Loại xe không được để trống") VehicleType vehicleType,
        @NotBlank(message = "Loại thùng xe không được để trống") @Size(max = 40) String bodyTypeCode,
        @NotNull(message = "Tải trọng không được để trống") @Positive(message = "Tải trọng phải lớn hơn 0") @Max(value = 60000, message = "Tải trọng tối đa 60.000 kg") Integer maxPayloadKg,
        @NotNull(message = "Chiều dài không được để trống") @Positive(message = "Chiều dài phải lớn hơn 0") @Max(value = 2000, message = "Chiều dài tối đa 2.000 cm") Integer lengthCm,
        @NotNull(message = "Chiều rộng không được để trống") @Positive(message = "Chiều rộng phải lớn hơn 0") @Max(value = 400, message = "Chiều rộng tối đa 400 cm") Integer widthCm,
        @NotNull(message = "Chiều cao không được để trống") @Positive(message = "Chiều cao phải lớn hơn 0") @Max(value = 500, message = "Chiều cao tối đa 500 cm") Integer heightCm,
        @NotNull(message = "Ngày hết hạn đăng kiểm không được để trống") LocalDate registrationExpiryDate) { }
