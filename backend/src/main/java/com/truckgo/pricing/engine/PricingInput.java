package com.truckgo.pricing.engine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Shipment facts the price depends on, all taken from the stored order (never from the client).
 * {@code pickupLocalTime} is in the business time zone; {@code manualAdjustment} is an admin correction (may be negative).
 */
public record PricingInput(BigDecimal distanceKm, int vehicleCount, BigDecimal totalWeightKg, BigDecimal totalVolumeM3, int totalQuantity,
                           int stopCount, LocalDateTime pickupLocalTime, List<SelectedService> services, BigDecimal manualAdjustment) {
    public record SelectedService(String code, String name, boolean numeric, String value) { }
}
