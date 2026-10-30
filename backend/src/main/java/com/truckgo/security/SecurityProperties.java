package com.truckgo.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("app.security")
public record SecurityProperties(Jwt jwt, Refresh refresh, LoginRateLimit loginRateLimit, List<String> corsAllowedOrigins, int bcryptStrength) {
    /** secret: Base64 HMAC key, at least 256 bit. Required outside the dev/test profiles. */
    public record Jwt(String secret, String issuer, Duration accessTokenTtl) { }

    public record Refresh(Duration ttl, Duration reuseGrace, String cookieName, String cookiePath, boolean cookieSecure, String cookieSameSite) { }

    public record LoginRateLimit(boolean enabled, int maxFailuresPerIdentifier, int maxAttemptsPerIp, Duration window) { }
}
