package com.truckgo.pricing.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.truckgo.geo.ProviderThrottle;
import com.truckgo.shipment.ShipmentStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared logic of providers that route between coordinates and answer in the OSRM format
 * ({@code {"code":"Ok","routes":[{"distance":m,"duration":s}]}}): OSRM and Mapbox Directions. Every stop needs
 * coordinates; otherwise the quote waits for an admin-verified distance. The single route through all stops in order
 * gives the total distance (sum of its legs).
 */
abstract class CoordinateRouteService implements RouteDistanceService {
    private static final Logger log = LoggerFactory.getLogger(CoordinateRouteService.class);
    private static final BigDecimal METERS_PER_KM = BigDecimal.valueOf(1000);

    private final RouteCall call;
    private final BigDecimal maxReasonableKm;
    private final ProviderThrottle throttle;
    private final int maxWaypoints;

    CoordinateRouteService(RouteCall call, BigDecimal maxReasonableKm, ProviderThrottle throttle, int maxWaypoints) {
        this.call = call;
        this.maxReasonableKm = maxReasonableKm;
        this.throttle = throttle;
        this.maxWaypoints = maxWaypoints;
    }

    /** Request URL for "lng,lat;lng,lat;…" (credentials included by the subclass, never logged). */
    abstract URI uri(String coordinates);

    @Override
    public RouteDistanceResult calculateDistance(List<ShipmentStop> stops) {
        String provider = name();
        if (stops.size() < 2) return RouteDistanceResult.unavailable(provider, "NOT_ENOUGH_STOPS", "Cần ít nhất hai điểm dừng để tính quãng đường");
        if (stops.size() > maxWaypoints)
            return RouteDistanceResult.unavailable(provider, "TOO_MANY_STOPS", "Đơn có quá nhiều điểm dừng cho dịch vụ bản đồ; quản trị viên sẽ xác nhận quãng đường");
        if (stops.stream().anyMatch(s -> s.getLatitude() == null || s.getLongitude() == null))
            return RouteDistanceResult.unavailable(provider, "MISSING_COORDINATES", "Có điểm dừng chưa có tọa độ; quản trị viên sẽ xác nhận quãng đường");
        if (throttle != null && !throttle.acquire())
            return RouteDistanceResult.unavailable(provider, "PROVIDER_BUSY", "Dịch vụ định tuyến đang bận; quản trị viên sẽ xác nhận quãng đường");
        String coordinates = stops.stream().map(s -> s.getLongitude().toPlainString() + "," + s.getLatitude().toPlainString()).collect(Collectors.joining(";"));
        JsonNode body;
        try {
            body = call.get(uri(coordinates), provider).body();
        } catch (RouteCall.FailedException ex) {
            return RouteCall.unavailable(provider, ex);
        }
        String code = body == null ? "EMPTY" : body.path("code").asText("EMPTY");
        if (!"Ok".equals(code)) {
            log.warn("Route provider {} returned code {}", provider, code);
            String reason = "NoRoute".equals(code) || "NoSegment".equals(code) ? "ROUTE_NOT_FOUND"
                    : "ProfileNotFound".equals(code) || "InvalidInput".equals(code) || "InvalidQuery".equals(code) ? "INVALID_ROUTE_REQUEST" : "PROVIDER_ERROR";
            return RouteDistanceResult.unavailable(provider, reason, "Dịch vụ định tuyến không tìm được tuyến đường (" + code + "); quản trị viên sẽ xác nhận quãng đường");
        }
        JsonNode route = body.path("routes").path(0);
        BigDecimal km = BigDecimal.valueOf(route.path("distance").asDouble(0)).divide(METERS_PER_KM, 2, RoundingMode.HALF_UP);
        if (km.signum() <= 0 || km.compareTo(maxReasonableKm) > 0)
            return RouteDistanceResult.unavailable(provider, "UNREASONABLE_DISTANCE", "Quãng đường trả về (" + km.toPlainString() + " km) không hợp lý; cần quản trị viên kiểm tra");
        return RouteDistanceResult.of(km, (int) Math.round(route.path("duration").asDouble(0) / 60.0), provider, null);
    }
}
