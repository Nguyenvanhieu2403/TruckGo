package com.truckgo.pricing.route;

import com.truckgo.geo.ProviderThrottle;

import java.math.BigDecimal;
import java.net.URI;

/**
 * OSRM driving route through the stops in order (free, OpenStreetMap data; coordinates required). The public demo
 * server is for light use only (one request per second, throttled); set OSRM_BASE_URL to your own OSRM for production.
 */
public class OsrmRouteDistanceService extends CoordinateRouteService {
    public static final String PROVIDER = "osrm";
    private final String baseUrl;

    public OsrmRouteDistanceService(RouteCall call, String baseUrl, BigDecimal maxReasonableKm, ProviderThrottle throttle) {
        super(call, maxReasonableKm, throttle, 100);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override public String name() { return PROVIDER; }

    @Override URI uri(String coordinates) { return URI.create(baseUrl + "/route/v1/driving/" + coordinates + "?overview=false&steps=false"); }
}
