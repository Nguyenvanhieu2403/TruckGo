package com.truckgo.pricing.config;

import com.truckgo.pricing.engine.PricingRates;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Entity → engine snapshot. */
final class PricingRatesMapper {
    private static final Map<String, DayOfWeek> DAYS = Map.of("MON", DayOfWeek.MONDAY, "TUE", DayOfWeek.TUESDAY, "WED", DayOfWeek.WEDNESDAY,
            "THU", DayOfWeek.THURSDAY, "FRI", DayOfWeek.FRIDAY, "SAT", DayOfWeek.SATURDAY, "SUN", DayOfWeek.SUNDAY);

    private PricingRatesMapper() { }

    static PricingRates toRates(PricingConfig c, List<PricingServiceFee> fees) {
        return new PricingRates(c.getId(), c.getName(), c.getVehicleTypeCode(), c.getBasePrice(), c.getPricePerKm(), c.getIncludedDistanceKm(),
                c.getIncludedWeightKg(), c.getPricePerExtraKg(), c.getIncludedVolumeM3(), c.getPricePerExtraM3(), c.getAdditionalStopPrice(), c.getMinimumPrice(),
                c.getCurrency(),
                fees.stream().filter(PricingServiceFee::isActive)
                        .map(f -> new PricingRates.ServiceFee(f.getServiceCode(), f.getServiceOptionValue(), f.getPricingType(), f.getAmount(), f.getPercentage())).toList(),
                c.getTimeRules().stream().map(r -> new PricingRates.TimeRule(r.getName(), days(r.getDaysOfWeek()), r.getStartTime(), r.getEndTime(),
                        r.getFeeType(), r.getAmount(), r.getPercentage())).toList(),
                c.getExtraFees().stream().map(f -> new PricingRates.ExtraFee(f.getCode(), f.getName(), f.getFeeType(), f.getAmount(), f.getPercentage())).toList());
    }

    static Set<DayOfWeek> days(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).map(DAYS::get).collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }
}
