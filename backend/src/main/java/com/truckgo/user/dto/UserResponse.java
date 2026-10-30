package com.truckgo.user.dto;

import com.truckgo.user.User;
import com.truckgo.user.UserRole;
import com.truckgo.user.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String fullName, String email, String phone, UserRole role, UserStatus status,
                           boolean emailVerified, boolean phoneVerified, Instant createdAt, Instant lastLoginAt) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getRole(), user.getStatus(),
                user.isEmailVerified(), user.isPhoneVerified(), user.getCreatedAt(), user.getLastLoginAt());
    }
}
