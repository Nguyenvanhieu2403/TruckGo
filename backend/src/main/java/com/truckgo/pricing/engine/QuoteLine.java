package com.truckgo.pricing.engine;

import java.math.BigDecimal;

/**
 * One breakdown row; {@code amount} is whole VND. appliesTo is ORDER for order-wide lines and STOP for services
 * ordered at particular stops, whose sequence numbers are listed in {@code stopSequences}.
 */
public record QuoteLine(PriceComponent component, String code, String name, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
                        String appliesTo, java.util.List<Integer> stopSequences) {
    public QuoteLine(PriceComponent component, String code, String name, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
        this(component, code, name, description, quantity, unitPrice, amount, "ORDER", null);
    }
}
