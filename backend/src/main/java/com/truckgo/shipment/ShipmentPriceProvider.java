package com.truckgo.shipment;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Implemented by the pricing module so order views can show the current price state without the shipment module
 * depending on pricing.
 */
public interface ShipmentPriceProvider {
    enum State { NOT_QUOTED, AWAITING_REVIEW, QUOTED, ACCEPTED }

    record CurrentPrice(State state, BigDecimal amount) { }

    Optional<CurrentPrice> currentPrice(UUID shipmentId);
}
