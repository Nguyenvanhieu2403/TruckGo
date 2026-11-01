package com.truckgo.pricing.route;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * app.maps.*: routeProvider none (manual), google, mapbox or osrm. Keys/tokens come from the environment only
 * (GOOGLE_MAPS_API_KEY, MAPBOX_ACCESS_TOKEN). maxReasonableKm rejects answers that are obviously wrong for a domestic
 * road trip. Exactly one provider is used; there is no automatic fallback to another one.
 */
@ConfigurationProperties("app.maps")
public record RouteProperties(String routeProvider, Google google, Mapbox mapbox, BigDecimal maxReasonableKm, RouteRetry routeRetry, RouteCache routeCache,
                              RouteQuota routeQuota) {
    public record Google(String apiKey, String baseUrl, Duration connectTimeout, Duration readTimeout) { }
    public record Mapbox(String accessToken, String baseUrl) { }
    /** Attempts per call for transient failures (timeouts, 5xx); input errors and quota answers are never retried. */
    public record RouteRetry(int maxAttempts, Duration backoff) { }
    /** version: bump to drop every cached route (the provider name and URL are part of the key as well). */
    public record RouteCache(Duration ttl, String version, Duration lockWait) { }
    /** Provider calls allowed per hour / per day for the whole application; 0 = unlimited. */
    public record RouteQuota(long perHour, long perDay) { }
}
