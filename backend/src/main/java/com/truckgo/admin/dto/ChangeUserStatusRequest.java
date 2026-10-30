package com.truckgo.admin.dto;

import com.truckgo.user.UserStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangeUserStatusRequest(
        @NotNull(message = "Trạng thái không được để trống") UserStatus status,
        @Size(max = 500, message = "Lý do tối đa 500 ký tự") String reason) { }
