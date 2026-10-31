package com.truckgo.pricing.engine;

import java.math.BigDecimal;

/** One breakdown row; {@code amount} is whole VND. */
public record QuoteLine(PriceComponent component, String code, String name, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) { }
