package com.truckgo.geo;

import java.util.Optional;

/**
 * Seam for a map vendor (Google Maps, Mapbox, OpenStreetMap/Nominatim…). Implementations read their API key from
 * configuration; none is hard-coded. Returning empty means "unknown" — callers must never invent coordinates.
 */
public interface GeocodingProvider {
    Optional<GeoPoint> geocode(String fullAddress);

    String name();
}
