package com.truckgo.pricing.route;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * app.maps.*: routeProvider "none" (manual) or "google". The Google API key comes from GOOGLE_MAPS_API_KEY only.
 * maxReasonableKm rejects provider answers that are obviously wrong for a domestic road trip.
 */
@ConfigurationProperties("app.maps")
public record RouteProperties(String routeProvider, Google google, BigDecimal maxReasonableKm) {
    public record Google(String apiKey, String baseUrl, Duration connectTimeout, Duration readTimeout) { }
}
