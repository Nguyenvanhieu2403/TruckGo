package com.truckgo.pricing.route;

import java.math.BigDecimal;

/**
 * Outcome of a route lookup. When {@code available} is false, distance is null and {@code reasonCode}/{@code message}
 * explain why; the quote then waits for an admin to enter a verified distance.
 */
public record RouteDistanceResult(boolean available, BigDecimal distanceKm, Integer durationMinutes, String provider,
                                  String encodedPolyline, String reasonCode, String message) {
    public static RouteDistanceResult of(BigDecimal distanceKm, Integer durationMinutes, String provider, String encodedPolyline) {
        return new RouteDistanceResult(true, distanceKm, durationMinutes, provider, encodedPolyline, null, null);
    }

    public static RouteDistanceResult unavailable(String provider, String reasonCode, String message) {
        return new RouteDistanceResult(false, null, null, provider, null, reasonCode, message);
    }
}
