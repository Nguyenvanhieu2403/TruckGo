package com.truckgo.pricing.engine;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable snapshot of a pricing config; stored as JSON on every quote so later edits never change old quotes. */
public record PricingRates(UUID configId, String configName, String vehicleTypeCode, BigDecimal basePrice, BigDecimal pricePerKm,
                           BigDecimal includedDistanceKm, BigDecimal includedWeightKg, BigDecimal pricePerExtraKg, BigDecimal includedVolumeM3,
                           BigDecimal pricePerExtraM3, BigDecimal additionalStopPrice, BigDecimal minimumPrice, String currency,
                           List<ServiceFee> serviceFees, List<TimeRule> timeRules, List<ExtraFee> extraFees) {

    /** {@code optionValue} null = any value of the service. */
    public record ServiceFee(String serviceCode, String optionValue, FeeType type, BigDecimal amount, BigDecimal percentage) { }

    /** {@code days} empty = every day; a window with start after end crosses midnight. */
    public record TimeRule(String name, Set<DayOfWeek> days, LocalTime start, LocalTime end, FeeType type, BigDecimal amount, BigDecimal percentage) { }

    public record ExtraFee(String code, String name, FeeType type, BigDecimal amount, BigDecimal percentage) { }
}
