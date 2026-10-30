package com.truckgo.driver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Only licence data is driver-editable; review fields are not part of the request. */
public record UpdateDriverProfileRequest(
        @NotBlank(message = "Số giấy phép lái xe không được để trống") @Size(max = 20, message = "Số giấy phép quá dài") String licenseNumber,
        @NotBlank(message = "Hạng giấy phép không được để trống") @Size(max = 5, message = "Hạng giấy phép không hợp lệ") String licenseClass) { }
