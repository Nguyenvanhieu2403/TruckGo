package com.truckgo.pricing.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.truckgo.shipment.ShipmentStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Google Maps Directions API: driving route through the stops in the given order (no reordering), distance summed
 * over all legs. Coordinates are used when the customer entered them, otherwise the structured address.
 * Errors and timeouts become "unavailable"; the API key is never logged (HTTP client messages contain the URL).
 */
public class GoogleRouteDistanceService implements RouteDistanceService {
    public static final String PROVIDER = "google";
    private static final Logger log = LoggerFactory.getLogger(GoogleRouteDistanceService.class);
    private static final BigDecimal METERS_PER_KM = BigDecimal.valueOf(1000);

    private final RestClient http;
    private final String apiKey;
    private final String baseUrl;
    private final BigDecimal maxReasonableKm;

    public GoogleRouteDistanceService(RestClient http, RouteProperties properties) {
        this.http = http;
        this.apiKey = properties.google().apiKey();
        this.baseUrl = properties.google().baseUrl();
        this.maxReasonableKm = properties.maxReasonableKm();
    }

    @Override public RouteDistanceResult calculateDistance(List<ShipmentStop> stops) {
        if (stops.size() < 2) return RouteDistanceResult.unavailable(PROVIDER, "NOT_ENOUGH_STOPS", "Cần ít nhất hai điểm dừng để tính quãng đường");
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("origin", point(stops.get(0)))
                .queryParam("destination", point(stops.get(stops.size() - 1)))
                .queryParam("mode", "driving").queryParam("region", "vn").queryParam("language", "vi")
                .queryParam("key", apiKey);
        if (stops.size() > 2) uri.queryParam("waypoints", stops.subList(1, stops.size() - 1).stream().map(GoogleRouteDistanceService::point).collect(Collectors.joining("|")));
        JsonNode body;
        try {
            body = http.get().uri(uri.encode().build().toUri()).retrieve().body(JsonNode.class);
        } catch (RuntimeException ex) {
            log.warn("Route provider call failed: {}", ex.getClass().getSimpleName());
            return RouteDistanceResult.unavailable(PROVIDER, "PROVIDER_ERROR", "Không kết nối được dịch vụ bản đồ; quản trị viên sẽ xác nhận quãng đường");
        }
        String status = body == null ? "EMPTY" : body.path("status").asText("EMPTY");
        if (!"OK".equals(status)) {
            log.warn("Route provider returned status {}", status);
            String code = "ZERO_RESULTS".equals(status) || "NOT_FOUND".equals(status) ? "ROUTE_NOT_FOUND" : "PROVIDER_ERROR";
            return RouteDistanceResult.unavailable(PROVIDER, code, "Dịch vụ bản đồ không tìm được tuyến đường (" + status + "); quản trị viên sẽ xác nhận quãng đường");
        }
        JsonNode route = body.path("routes").path(0);
        long meters = 0;
        long seconds = 0;
        for (JsonNode leg : route.path("legs")) {
            meters += leg.path("distance").path("value").asLong(0);
            seconds += leg.path("duration").path("value").asLong(0);
        }
        BigDecimal km = BigDecimal.valueOf(meters).divide(METERS_PER_KM, 2, RoundingMode.HALF_UP);
        if (km.signum() <= 0 || km.compareTo(maxReasonableKm) > 0)
            return RouteDistanceResult.unavailable(PROVIDER, "UNREASONABLE_DISTANCE", "Quãng đường trả về (" + km.toPlainString() + " km) không hợp lý; cần quản trị viên kiểm tra");
        String polyline = route.path("overview_polyline").path("points").asText(null);
        return RouteDistanceResult.of(km, (int) Math.round(seconds / 60.0), PROVIDER, polyline);
    }

    private static String point(ShipmentStop stop) {
        if (stop.getLatitude() != null && stop.getLongitude() != null)
            return stop.getLatitude().toPlainString() + "," + stop.getLongitude().toPlainString();
        return stop.getFullAddress();
    }
}
