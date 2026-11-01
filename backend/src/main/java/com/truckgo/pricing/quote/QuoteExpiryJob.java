package com.truckgo.pricing.quote;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Marks calculated quotes past their validity as EXPIRED, in batches. Safe with several backend instances: each batch
 * claims its rows with FOR UPDATE SKIP LOCKED and the update re-checks status and expiry, so a quote is expired once
 * and quotes being accepted at that moment (row lock) are skipped. Only status and updated_at change — never amounts
 * or the snapshot — and ACCEPTED, REJECTED and SUPERSEDED quotes are never touched. Reads still expire a due quote
 * themselves, so the API is exact even between two runs.
 */
@Component
@ConditionalOnProperty(value = "app.pricing.expiry-job-enabled", havingValue = "true", matchIfMissing = true)
public class QuoteExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(QuoteExpiryJob.class);
    static final String EXPIRE_BATCH = """
            UPDATE shipment_quotes SET status = 'EXPIRED', updated_at = ?, version = version + 1
             WHERE id IN (SELECT id FROM shipment_quotes
                           WHERE status = 'CALCULATED' AND expires_at <= ?
                           ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
               AND status = 'CALCULATED' AND expires_at <= ?
            RETURNING id""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final Counter expired;

    public QuoteExpiryJob(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, MeterRegistry metrics,
                          @Value("${app.pricing.expiry-batch-size:200}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
        this.batchSize = batchSize;
        this.expired = Counter.builder("truckgo.quotes.expired").description("Quotes expired by the scheduled job").register(metrics);
    }

    @Scheduled(fixedDelayString = "${app.pricing.expiry-interval:5m}", initialDelayString = "${app.pricing.expiry-interval:5m}")
    public void scheduled() { run(); }

    /** Expires every due quote, batch by batch; returns how many this instance expired. */
    public int run() {
        int total = 0;
        while (true) {
            Instant now = clock.instant();   // UTC instants on both sides of the comparison
            List<UUID> ids;
            try {
                ids = tx.execute(s -> jdbc.queryForList(EXPIRE_BATCH, UUID.class, Timestamp.from(now), Timestamp.from(now), batchSize, Timestamp.from(now)));
            } catch (RuntimeException ex) {
                log.warn("Quote expiry run stopped: {}", ex.getClass().getSimpleName());
                break;
            }
            int count = ids == null ? 0 : ids.size();
            total += count;
            expired.increment(count);
            if (count < batchSize) break;
        }
        if (total > 0) log.info("Quote expiry job expired {} quote(s)", total);
        return total;
    }
}
