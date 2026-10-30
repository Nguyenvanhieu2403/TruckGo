package com.truckgo.auth.dto;

import com.truckgo.user.dto.UserResponse;

/** The refresh token is never part of the body; it travels only in the HttpOnly cookie. */
public record AuthTokenResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
    public static AuthTokenResponse bearer(String accessToken, long expiresIn, UserResponse user) {
        return new AuthTokenResponse(accessToken, "Bearer", expiresIn, user);
    }
}
