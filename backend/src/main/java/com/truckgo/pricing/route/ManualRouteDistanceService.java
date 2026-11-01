package com.truckgo.pricing.route;

import com.truckgo.shipment.ShipmentStop;

import java.util.List;

/**
 * Used when no map provider is configured: the distance is unknown until an admin enters a verified value through
 * the manual-adjustment API. It never guesses.
 */
public class ManualRouteDistanceService implements RouteDistanceService {
    public static final String PROVIDER = "manual";

    @Override public String name() { return PROVIDER; }

    @Override public RouteDistanceResult calculateDistance(List<ShipmentStop> stops) {
        return RouteDistanceResult.unavailable(PROVIDER, "NO_ROUTE_PROVIDER",
                "Chưa cấu hình dịch vụ bản đồ; quản trị viên sẽ xác nhận quãng đường cho đơn này");
    }
}
