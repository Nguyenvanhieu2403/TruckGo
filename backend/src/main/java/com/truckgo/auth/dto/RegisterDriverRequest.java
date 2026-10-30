package com.truckgo.auth.dto;

import com.truckgo.user.StrongPassword;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterDriverRequest(
        @NotBlank(message = "Họ tên không được để trống") @Size(min = 2, max = 100, message = "Họ tên dài 2–100 ký tự") String fullName,
        @Size(max = 254, message = "Email quá dài") String email,
        @Size(max = 20, message = "Số điện thoại quá dài") String phone,
        @NotNull(message = "Mật khẩu không được để trống") @StrongPassword String password,
        @NotNull(message = "Vui lòng xác nhận mật khẩu") String confirmPassword,
        @NotBlank(message = "Số giấy phép lái xe không được để trống") @Size(max = 20, message = "Số giấy phép quá dài") String licenseNumber,
        @NotBlank(message = "Hạng giấy phép không được để trống") @Size(max = 5, message = "Hạng giấy phép không hợp lệ") String licenseClass) implements AccountRegistration {

    @AssertTrue(message = "Cần cung cấp email hoặc số điện thoại")
    public boolean isContactProvided() { return AccountRegistration.hasText(email) || AccountRegistration.hasText(phone); }

    @AssertTrue(message = "Mật khẩu xác nhận không khớp")
    public boolean isConfirmPasswordMatching() { return password == null || password.equals(confirmPassword); }
}
