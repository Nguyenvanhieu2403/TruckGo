package com.truckgo.marketplace;

import com.truckgo.shipment.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Periodically expires published orders whose pickup time passed without a driver. Safe to run on several
 * instances: each order is handled under its row lock and re-checked, so it is expired exactly once.
 */
@Component
@ConditionalOnProperty(value = "app.marketplace.expiry-job-enabled", havingValue = "true", matchIfMissing = true)
public class MarketplaceExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(MarketplaceExpiryJob.class);

    private final ShipmentRepository shipments;
    private final MarketplaceLifecycle lifecycle;
    private final Clock clock;

    public MarketplaceExpiryJob(ShipmentRepository shipments, MarketplaceLifecycle lifecycle, Clock clock) {
        this.shipments = shipments;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.marketplace.expiry-interval:5m}", initialDelayString = "${app.marketplace.expiry-interval:5m}")
    public void run() {
        int expired = 0;
        for (UUID id : shipments.findOpenPickupBefore(clock.instant())) {
            try {
                if (lifecycle.expireIfDue(id)) expired++;
            } catch (RuntimeException ex) {
                log.error("Could not expire shipment {}: {}", id, ex.toString());
            }
        }
        if (expired > 0) log.info("Marketplace expiry job expired {} shipments", expired);
    }
}
