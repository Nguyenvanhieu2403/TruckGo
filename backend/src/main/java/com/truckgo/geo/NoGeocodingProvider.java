package com.truckgo.geo;

import java.util.Optional;

/** Default when no map provider is configured: addresses are entered manually and coordinates stay as given. */
public class NoGeocodingProvider implements GeocodingProvider {
    @Override public Optional<GeoPoint> geocode(String fullAddress) { return Optional.empty(); }
    @Override public String name() { return "none"; }
}
