package com.truckgo.pricing.route;

import com.truckgo.shipment.ShipmentStop;

import java.util.List;

/**
 * Road distance of a shipment's route through its stops in order. Implementations must return a real routed
 * distance or {@link RouteDistanceResult#unavailable}; a straight-line estimate must never be used for pricing.
 */
public interface RouteDistanceService {
    RouteDistanceResult calculateDistance(List<ShipmentStop> stops);
}
