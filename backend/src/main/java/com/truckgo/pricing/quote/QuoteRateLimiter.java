package com.truckgo.pricing.quote;

import com.truckgo.common.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Limits how many quote versions a customer creates: per order within a sliding window, and across all their orders
 * per hour. Counts come from shipment_quotes in PostgreSQL (created under the order's row lock), so the limit is exact
 * across instances and does not depend on Redis. Only new versions count: pressing "Tính giá" when nothing changed
 * returns the current quote and is never limited. Admin recalculations are not limited.
 */
@Component
public class QuoteRateLimiter {
    private final ShipmentQuoteRepository quotes;
    private final Clock clock;
    private final int perShipment;
    private final Duration shipmentWindow;
    private final int perCustomerHour;

    public QuoteRateLimiter(ShipmentQuoteRepository quotes, Clock clock,
                            @Value("${app.pricing.preview-limit.per-shipment:5}") int perShipment,
                            @Value("${app.pricing.preview-limit.shipment-window:10m}") Duration shipmentWindow,
                            @Value("${app.pricing.preview-limit.per-customer-hour:30}") int perCustomerHour) {
        this.quotes = quotes;
        this.clock = clock;
        this.perShipment = perShipment;
        this.shipmentWindow = shipmentWindow;
        this.perCustomerHour = perCustomerHour;
    }

    /** Throws 429 with Retry-After (seconds until the oldest counted version leaves its window). */
    public void check(UUID customerId, UUID shipmentId) {
        Instant now = clock.instant();
        if (perShipment > 0) {
            List<ShipmentQuote> recent = quotes.findByShipmentIdAndCreatedByAndCreatedAtAfterOrderByCreatedAtAsc(shipmentId, customerId, now.minus(shipmentWindow));
            if (recent.size() >= perShipment)
                throw new TooManyRequestsException("Bạn đã tính giá đơn này " + recent.size() + " lần trong " + shipmentWindow.toMinutes()
                        + " phút; vui lòng thử lại sau", retryAfter(recent.get(recent.size() - perShipment).getCreatedAt(), shipmentWindow, now));
        }
        if (perCustomerHour > 0) {
            List<ShipmentQuote> recent = quotes.findByCreatedByAndCreatedAtAfterOrderByCreatedAtAsc(customerId, now.minus(Duration.ofHours(1)));
            if (recent.size() >= perCustomerHour)
                throw new TooManyRequestsException("Bạn đã tính giá quá nhiều lần trong một giờ; vui lòng thử lại sau",
                        retryAfter(recent.get(recent.size() - perCustomerHour).getCreatedAt(), Duration.ofHours(1), now));
        }
    }

    private static long retryAfter(Instant oldestCounted, Duration window, Instant now) {
        return Math.max(1, Duration.between(now, oldestCounted.plus(window)).toSeconds() + 1);
    }
}
