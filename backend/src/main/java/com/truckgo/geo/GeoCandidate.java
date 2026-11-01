package com.truckgo.geo;

import java.math.BigDecimal;

/**
 * One address match from a provider. precision says how exact the point is; partialMatch means the provider matched
 * only part of the query. Only precise, full matches are ever stored without the user choosing them.
 */
public record GeoCandidate(String formattedAddress, BigDecimal latitude, BigDecimal longitude, Precision precision, boolean partialMatch, String placeId) {
    public enum Precision { EXACT, INTERPOLATED, CENTER, APPROXIMATE }

    /**
     * Good enough to fill blank coordinates automatically: the address itself (building, house number), not the
     * centre of a street, ward or district. Coarser matches are only used when the user picks them.
     */
    public boolean isPreciseFullMatch() { return !partialMatch && (precision == Precision.EXACT || precision == Precision.INTERPOLATED); }

    public GeoPoint point() { return new GeoPoint(latitude, longitude); }
}
