package com.truckgo.trip;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A validated coordinate pair, stored with 6 decimals (~0.1 m). */
public record GeoFix(BigDecimal latitude, BigDecimal longitude) {
    public GeoFix {
        if (latitude == null || longitude == null) throw new IllegalArgumentException("Thiếu vĩ độ hoặc kinh độ");
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0)
            throw new IllegalArgumentException("Vĩ độ phải trong khoảng -90 đến 90");
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0)
            throw new IllegalArgumentException("Kinh độ phải trong khoảng -180 đến 180");
        latitude = latitude.setScale(6, RoundingMode.HALF_UP);
        longitude = longitude.setScale(6, RoundingMode.HALF_UP);
    }

    /** Both or neither must be given; null when neither was. */
    public static GeoFix optional(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null && longitude == null) return null;
        return new GeoFix(latitude, longitude);
    }

    /** Great-circle distance in metres (haversine). */
    public double metersTo(GeoFix other) {
        double r = 6_371_000;
        double lat1 = Math.toRadians(latitude.doubleValue()), lat2 = Math.toRadians(other.latitude.doubleValue());
        double dLat = lat2 - lat1, dLon = Math.toRadians(other.longitude.doubleValue() - longitude.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
