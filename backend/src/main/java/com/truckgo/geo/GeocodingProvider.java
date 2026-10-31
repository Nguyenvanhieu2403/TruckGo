package com.truckgo.geo;

import java.util.List;
import java.util.Optional;

/**
 * Seam for a map vendor (Google Maps today; Mapbox, OpenStreetMap/Nominatim… implement the same two methods).
 * Implementations read their API key from configuration; none is hard-coded. An empty result means "unknown" —
 * callers must never invent coordinates.
 */
public interface GeocodingProvider {
    String name();

    /** False when no provider is configured: search is unavailable and addresses are entered manually. */
    default boolean enabled() { return true; }

    /**
     * Address matches for free text, best first, already validated (see {@link GeoValidator}).
     *
     * @throws GeocodingUnavailableException when the provider cannot answer right now
     */
    List<GeoCandidate> search(String query, int limit);

    /**
     * Coordinates for an address only when the provider has a precise, full match; empty otherwise and on any error,
     * so saving an order never depends on the map provider.
     */
    default Optional<GeoPoint> geocode(String fullAddress) {
        if (!enabled()) return Optional.empty();
        try {
            return search(fullAddress, 1).stream().filter(GeoCandidate::isPreciseFullMatch).findFirst().map(GeoCandidate::point);
        } catch (GeocodingUnavailableException ex) {
            return Optional.empty();
        }
    }
}
