package com.truckgo.user.dto;

import com.truckgo.user.StrongPassword;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "Vui lòng nhập mật khẩu hiện tại") @Size(max = 200, message = "Giá trị quá dài") String currentPassword,
        @NotNull(message = "Vui lòng nhập mật khẩu mới") @StrongPassword String newPassword,
        @NotNull(message = "Vui lòng xác nhận mật khẩu mới") String confirmNewPassword) {

    @AssertTrue(message = "Mật khẩu xác nhận không khớp")
    public boolean isConfirmNewPasswordMatching() { return newPassword == null || newPassword.equals(confirmNewPassword); }
}
