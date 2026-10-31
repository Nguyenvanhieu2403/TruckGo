package com.truckgo.marketplace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.marketplace.*: applicationCutoff is how long before pickup drivers stop being able to apply;
 * maxPendingPerDriver caps open applications per driver; cacheTtl bounds how stale the shared listing can be.
 */
@ConfigurationProperties("app.marketplace")
public record MarketplaceProperties(Duration applicationCutoff, int maxPendingPerDriver, Duration cacheTtl, Duration expiryInterval) { }
