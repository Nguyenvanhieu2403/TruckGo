package com.truckgo.pricing.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.truckgo.geo.ProviderThrottle;
import com.truckgo.shipment.ShipmentStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/**
 * OSRM driving route through the stops in order (free, OpenStreetMap data). OSRM routes between coordinates only,
 * so every stop needs latitude/longitude; otherwise the quote waits for an admin-verified distance as before.
 * The public demo server is for light use only (one request per second, throttled); run your own OSRM for production.
 */
public class OsrmRouteDistanceService implements RouteDistanceService {
    public static final String PROVIDER = "osrm";
    private static final Logger log = LoggerFactory.getLogger(OsrmRouteDistanceService.class);
    private static final BigDecimal METERS_PER_KM = BigDecimal.valueOf(1000);

    private final RestClient http;
    private final String baseUrl;
    private final BigDecimal maxReasonableKm;
    private final ProviderThrottle throttle;

    public OsrmRouteDistanceService(RestClient http, String baseUrl, BigDecimal maxReasonableKm, ProviderThrottle throttle) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.maxReasonableKm = maxReasonableKm;
        this.throttle = throttle;
    }

    @Override
    public RouteDistanceResult calculateDistance(List<ShipmentStop> stops) {
        if (stops.size() < 2) return RouteDistanceResult.unavailable(PROVIDER, "NOT_ENOUGH_STOPS", "Cần ít nhất hai điểm dừng để tính quãng đường");
        if (stops.stream().anyMatch(s -> s.getLatitude() == null || s.getLongitude() == null))
            return RouteDistanceResult.unavailable(PROVIDER, "MISSING_COORDINATES", "Có điểm dừng chưa có tọa độ; quản trị viên sẽ xác nhận quãng đường");
        if (!throttle.acquire())
            return RouteDistanceResult.unavailable(PROVIDER, "PROVIDER_BUSY", "Dịch vụ định tuyến đang bận; quản trị viên sẽ xác nhận quãng đường");
        String coordinates = stops.stream().map(s -> s.getLongitude().toPlainString() + "," + s.getLatitude().toPlainString()).collect(Collectors.joining(";"));
        JsonNode body;
        try {
            body = http.get().uri(URI.create(baseUrl + "/route/v1/driving/" + coordinates + "?overview=false&steps=false")).retrieve().body(JsonNode.class);
        } catch (RuntimeException ex) {
            log.warn("OSRM call failed: {}", ex.getClass().getSimpleName());
            return RouteDistanceResult.unavailable(PROVIDER, "PROVIDER_ERROR", "Không kết nối được dịch vụ định tuyến; quản trị viên sẽ xác nhận quãng đường");
        }
        String code = body == null ? "EMPTY" : body.path("code").asText("EMPTY");
        if (!"Ok".equals(code)) {
            log.warn("OSRM returned code {}", code);
            return RouteDistanceResult.unavailable(PROVIDER, "NoRoute".equals(code) ? "ROUTE_NOT_FOUND" : "PROVIDER_ERROR",
                    "Dịch vụ định tuyến không tìm được tuyến đường (" + code + "); quản trị viên sẽ xác nhận quãng đường");
        }
        JsonNode route = body.path("routes").path(0);
        BigDecimal km = BigDecimal.valueOf(route.path("distance").asDouble(0)).divide(METERS_PER_KM, 2, RoundingMode.HALF_UP);
        if (km.signum() <= 0 || km.compareTo(maxReasonableKm) > 0)
            return RouteDistanceResult.unavailable(PROVIDER, "UNREASONABLE_DISTANCE", "Quãng đường trả về (" + km.toPlainString() + " km) không hợp lý; cần quản trị viên kiểm tra");
        return RouteDistanceResult.of(km, (int) Math.round(route.path("duration").asDouble(0) / 60.0), PROVIDER, null);
    }
}
