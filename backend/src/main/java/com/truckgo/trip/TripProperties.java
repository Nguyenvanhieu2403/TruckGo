package com.truckgo.trip;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.trip.*: delivery confirmation OTP and proof photos.
 * otpSecret keys the HMAC of stored codes; when blank the JWT secret is used.
 */
@ConfigurationProperties("app.trip")
public record TripProperties(Duration otpTtl, int otpMaxAttempts, Duration otpResendCooldown, int otpMaxSends, String otpSecret,
                             boolean requireDeliveryPhoto, int maxTripPhotos) { }
