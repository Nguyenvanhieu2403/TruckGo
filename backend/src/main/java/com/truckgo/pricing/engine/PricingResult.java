package com.truckgo.pricing.engine;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Lines plus per-component sums; {@code total} is by construction the sum of all line amounts. */
public record PricingResult(List<QuoteLine> lines, Map<PriceComponent, BigDecimal> components, BigDecimal total) {
    public static PricingResult of(List<QuoteLine> lines) {
        Map<PriceComponent, BigDecimal> components = new EnumMap<>(PriceComponent.class);
        for (PriceComponent c : PriceComponent.values()) components.put(c, BigDecimal.ZERO);
        for (QuoteLine line : lines) components.merge(line.component(), line.amount(), BigDecimal::add);
        BigDecimal total = lines.stream().map(QuoteLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PricingResult(List.copyOf(lines), Map.copyOf(components), total);
    }

    public BigDecimal component(PriceComponent c) { return components.get(c); }
}
