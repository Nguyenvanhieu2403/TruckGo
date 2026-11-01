package com.truckgo.pricing.route;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Mapbox Directions API (driving profile), at most 25 coordinates per request. The access token comes from
 * MAPBOX_ACCESS_TOKEN only and is never logged.
 */
public class MapboxRouteDistanceService extends CoordinateRouteService {
    public static final String PROVIDER = "mapbox";
    private final String baseUrl;
    private final String accessToken;

    public MapboxRouteDistanceService(RouteCall call, String baseUrl, String accessToken, BigDecimal maxReasonableKm) {
        super(call, maxReasonableKm, null, 25);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.accessToken = accessToken;
    }

    @Override public String name() { return PROVIDER; }

    @Override URI uri(String coordinates) {
        return URI.create(baseUrl + "/directions/v5/mapbox/driving/" + coordinates + "?overview=false&steps=false&access_token="
                + URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
    }
}
