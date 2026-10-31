package com.truckgo.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds photo objects in storage that no photo row refers to (e.g. an upload whose compensation also failed) and
 * hands them to the deletion queue. Safety rules, in order:
 * <ul>
 *   <li>only keys under {@code shipments/} are considered, and only objects older than orphanMinAge;</li>
 *   <li>if the database cannot be asked, the run stops — "not found" is never assumed from an error;</li>
 *   <li>if a suspiciously large share of a batch looks orphaned, nothing is deleted and an error is logged;</li>
 *   <li>at most orphanMaxDeletesPerRun objects per run, and in dry-run mode (default) nothing is deleted at all.</li>
 * </ul>
 * The scan resumes where the previous run stopped and wraps around at the end of the bucket.
 */
@Component
@ConditionalOnProperty(value = "app.storage.cleanup.orphan-sweep-enabled", havingValue = "true", matchIfMissing = true)
public class OrphanObjectSweeper {
    static final String PREFIX = "shipments/";
    private static final Logger log = LoggerFactory.getLogger(OrphanObjectSweeper.class);

    public record Report(int scanned, int candidates, int orphans, int queued, boolean dryRun, String stoppedBecause) { }

    private final StorageService storage;
    private final NamedParameterJdbcTemplate jdbc;
    private final StorageDeletionQueue queue;
    private final StorageDeletionProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private volatile String cursor;

    public OrphanObjectSweeper(StorageService storage, NamedParameterJdbcTemplate jdbc, StorageDeletionQueue queue, StorageDeletionProperties properties,
                               PlatformTransactionManager transactions, Clock clock) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.queue = queue;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.storage.cleanup.orphan-interval:6h}", initialDelayString = "${app.storage.cleanup.orphan-initial-delay:30m}")
    public void scheduled() { sweep(properties.orphanDryRun()); }

    public Report sweep(boolean dryRun) {
        List<StorageService.StoredObject> page;
        try {
            page = storage.list(PREFIX, cursor, properties.orphanScanLimit());
        } catch (RuntimeException ex) {
            log.warn("Orphan sweep skipped, storage unavailable: {}", ex.getClass().getSimpleName());
            return new Report(0, 0, 0, 0, dryRun, "STORAGE_UNAVAILABLE");
        }
        cursor = page.size() < properties.orphanScanLimit() ? null : page.getLast().key();   // wrap around at the end
        Instant cutoff = clock.instant().minus(properties.orphanMinAge());
        List<String> candidates = page.stream().filter(o -> o.key().startsWith(PREFIX) && o.lastModified() != null && o.lastModified().isBefore(cutoff))
                .map(StorageService.StoredObject::key).toList();
        if (candidates.isEmpty()) return new Report(page.size(), 0, 0, 0, dryRun, null);

        Set<String> known;
        try {
            MapSqlParameterSource keys = new MapSqlParameterSource("keys", candidates);
            known = new HashSet<>(jdbc.queryForList("SELECT file_key FROM shipment_attachments WHERE file_key IN (:keys)", keys, String.class));
            known.addAll(jdbc.queryForList("SELECT file_key FROM storage_deletion_tasks WHERE status = 'PENDING' AND file_key IN (:keys)", keys, String.class));
        } catch (RuntimeException ex) {
            log.error("Orphan sweep stopped: database check failed ({}); nothing deleted", ex.getClass().getSimpleName());
            return new Report(page.size(), candidates.size(), 0, 0, dryRun, "DATABASE_UNAVAILABLE");
        }
        List<String> orphans = candidates.stream().filter(k -> !known.contains(k)).toList();
        if (orphans.isEmpty()) return new Report(page.size(), candidates.size(), 0, 0, dryRun, null);
        if (candidates.size() >= 20 && orphans.size() > candidates.size() * properties.orphanMaxRatio()) {
            log.error("Orphan sweep stopped: {} of {} objects look orphaned, above the safety ratio; check the database and bucket configuration",
                    orphans.size(), candidates.size());
            return new Report(page.size(), candidates.size(), orphans.size(), 0, dryRun, "RATIO_EXCEEDED");
        }
        List<String> selected = orphans.stream().limit(properties.orphanMaxDeletesPerRun()).toList();
        if (dryRun) {
            selected.forEach(k -> log.info("Orphan sweep (dry run) would delete key={}", k));
            return new Report(page.size(), candidates.size(), orphans.size(), 0, true, null);
        }
        try {
            tx.executeWithoutResult(s -> queue.enqueue(selected, StorageDeletionTask.Reason.ORPHAN, null));
        } catch (RuntimeException ex) {
            log.error("Orphan sweep could not queue deletions: {}", ex.getClass().getSimpleName());
            return new Report(page.size(), candidates.size(), orphans.size(), 0, false, "DATABASE_UNAVAILABLE");
        }
        log.info("Orphan sweep queued {} objects for deletion", selected.size());
        return new Report(page.size(), candidates.size(), orphans.size(), selected.size(), false, null);
    }

    void resetCursor() { cursor = null; }
}
