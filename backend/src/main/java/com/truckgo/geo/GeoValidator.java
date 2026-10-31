package com.truckgo.geo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** Rejects provider answers that cannot be a delivery point: missing, out of range, or outside the service area. */
public final class GeoValidator {
    private final GeoProperties properties;

    public GeoValidator(GeoProperties properties) { this.properties = properties; }

    public Optional<GeoCandidate> validate(GeoCandidate c) {
        if (c == null || c.latitude() == null || c.longitude() == null || c.formattedAddress() == null || c.formattedAddress().isBlank()) return Optional.empty();
        BigDecimal lat = c.latitude().setScale(6, RoundingMode.HALF_UP), lng = c.longitude().setScale(6, RoundingMode.HALF_UP);
        if (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0) return Optional.empty();
        if (lat.signum() == 0 && lng.signum() == 0) return Optional.empty();   // "null island" is a provider failure, not an address
        if (lat.compareTo(properties.minLatitude()) < 0 || lat.compareTo(properties.maxLatitude()) > 0
                || lng.compareTo(properties.minLongitude()) < 0 || lng.compareTo(properties.maxLongitude()) > 0) return Optional.empty();
        return Optional.of(new GeoCandidate(c.formattedAddress().trim(), lat, lng, c.precision(), c.partialMatch(), c.placeId()));
    }
}
