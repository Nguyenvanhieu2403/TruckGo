package com.truckgo.geo;

import java.util.List;

/** Default when no map provider is configured: addresses are entered manually and coordinates stay as given. */
public class NoGeocodingProvider implements GeocodingProvider {
    @Override public String name() { return "none"; }
    @Override public boolean enabled() { return false; }
    @Override public List<GeoCandidate> search(String query, int limit) { throw new GeocodingUnavailableException("Chưa cấu hình dịch vụ bản đồ"); }
}
