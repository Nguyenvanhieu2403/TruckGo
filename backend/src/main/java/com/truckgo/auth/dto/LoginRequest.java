package com.truckgo.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Vui lòng nhập email hoặc số điện thoại") @Size(max = 254, message = "Giá trị quá dài") String identifier,
        @NotBlank(message = "Vui lòng nhập mật khẩu") @Size(max = 200, message = "Giá trị quá dài") String password) { }
