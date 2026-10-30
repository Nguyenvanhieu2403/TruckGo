package com.truckgo.user.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial update. A null field is left unchanged; an empty string removes email/phone (at least one must remain).
 * Role, status and verification flags are not accepted.
 */
public record UpdateProfileRequest(
        @Size(min = 2, max = 100, message = "Họ tên dài 2–100 ký tự") String fullName,
        @Size(max = 254, message = "Email quá dài") String email,
        @Size(max = 20, message = "Số điện thoại quá dài") String phone) { }
