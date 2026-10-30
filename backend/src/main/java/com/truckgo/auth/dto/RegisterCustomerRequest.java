package com.truckgo.auth.dto;

import com.truckgo.user.StrongPassword;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** No role field: the role is assigned by the server and any "role" property in the JSON is ignored. */
public record RegisterCustomerRequest(
        @NotBlank(message = "Họ tên không được để trống") @Size(min = 2, max = 100, message = "Họ tên dài 2–100 ký tự") String fullName,
        @Size(max = 254, message = "Email quá dài") String email,
        @Size(max = 20, message = "Số điện thoại quá dài") String phone,
        @NotNull(message = "Mật khẩu không được để trống") @StrongPassword String password,
        @NotNull(message = "Vui lòng xác nhận mật khẩu") String confirmPassword) implements AccountRegistration {

    @AssertTrue(message = "Cần cung cấp email hoặc số điện thoại")
    public boolean isContactProvided() { return AccountRegistration.hasText(email) || AccountRegistration.hasText(phone); }

    @AssertTrue(message = "Mật khẩu xác nhận không khớp")
    public boolean isConfirmPasswordMatching() { return password == null || password.equals(confirmPassword); }
}
