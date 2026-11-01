package com.truckgo.marketplace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.marketplace.*: applicationCutoff is how long before pickup drivers stop being able to apply;
 * maxPendingPerDriver caps open applications per driver; cacheTtl bounds how stale the shared listing can be;
 * scheduleBuffer and defaultTripDuration define how long an assignment keeps its driver and vehicle busy.
 */
@ConfigurationProperties("app.marketplace")
public record MarketplaceProperties(Duration applicationCutoff, int maxPendingPerDriver, Duration cacheTtl, Duration expiryInterval,
                                    Duration scheduleBuffer, Duration defaultTripDuration) {
    public MarketplaceProperties {
        if (scheduleBuffer == null) scheduleBuffer = Duration.ofHours(1);
        if (defaultTripDuration == null || defaultTripDuration.isZero() || defaultTripDuration.isNegative()) defaultTripDuration = Duration.ofHours(8);
    }
}
