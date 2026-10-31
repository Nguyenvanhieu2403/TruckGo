package com.truckgo.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.storage.cleanup.*: retry policy of the deletion queue and the orphan sweep.
 * <ul>
 *   <li>orphanDryRun (default true): the sweep only logs what it would delete until an operator turns it off.</li>
 *   <li>orphanMinAge: objects younger than this are never treated as orphans (uploads in flight, slow commits).</li>
 *   <li>orphanMaxRatio: if more than this share of a scanned batch looks orphaned, nothing is deleted (a sign the app
 *       is pointed at the wrong database or bucket).</li>
 * </ul>
 */
@ConfigurationProperties("app.storage.cleanup")
public record StorageDeletionProperties(Duration retryInterval, int batchSize, int maxAttempts, Duration maxDelay, Duration lease,
                                        boolean orphanSweepEnabled, boolean orphanDryRun, Duration orphanMinAge, int orphanScanLimit,
                                        int orphanMaxDeletesPerRun, double orphanMaxRatio) { }
