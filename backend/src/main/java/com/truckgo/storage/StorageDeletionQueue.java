package com.truckgo.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Deletes storage objects reliably. {@link #enqueue} records the keys in the caller's transaction, so nothing happens
 * if it rolls back; after commit one attempt is made right away and a scheduled job retries failures with backoff.
 * Calls to storage never run inside a database transaction. Before deleting, the queue re-checks that no photo row
 * still points at the key, so a live photo can never be removed by mistake.
 */
@Component
public class StorageDeletionQueue {
    private static final Logger log = LoggerFactory.getLogger(StorageDeletionQueue.class);

    private final StorageDeletionTaskRepository tasks;
    private final StorageService storage;
    private final JdbcTemplate jdbc;
    private final StorageDeletionProperties properties;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;
    private final Clock clock;

    public StorageDeletionQueue(StorageDeletionTaskRepository tasks, StorageService storage, JdbcTemplate jdbc, StorageDeletionProperties properties,
                                PlatformTransactionManager transactions, Clock clock) {
        this.tasks = tasks;
        this.storage = storage;
        this.jdbc = jdbc;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Must run inside the transaction whose commit orphans the objects. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Collection<String> keys, StorageDeletionTask.Reason reason, UUID shipmentId) {
        if (keys.isEmpty()) return;
        Instant now = clock.instant();
        List<StorageDeletionTask> rows = keys.stream().distinct()
                .filter(k -> !tasks.existsByFileKeyAndStatus(k, StorageDeletionTask.Status.PENDING))
                .map(k -> new StorageDeletionTask(k, reason, shipmentId, now)).toList();
        tasks.saveAllAndFlush(rows);
        List<UUID> ids = rows.stream().map(StorageDeletionTask::getId).toList();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { ids.forEach(StorageDeletionQueue.this::attempt); }
        });
    }

    /**
     * For a failed upload whose metadata insert rolled back: tries to delete the object now and, if storage is down
     * too, records it in a separate transaction. If even that fails the orphan sweep will find the object later.
     */
    public void compensate(String key, UUID shipmentId) {
        try {
            storage.delete(key);
            return;
        } catch (RuntimeException ex) {
            log.warn("Compensating delete failed, queueing key={}", key);
        }
        try {
            newTx.executeWithoutResult(s -> tasks.saveAndFlush(new StorageDeletionTask(key, StorageDeletionTask.Reason.UPLOAD_COMPENSATION, shipmentId, clock.instant())));
        } catch (RuntimeException ex) {
            log.error("Could not queue deletion of orphaned upload key={}; the orphan sweep will pick it up", key);
        }
    }

    @Scheduled(fixedDelayString = "${app.storage.cleanup.retry-interval:1m}", initialDelayString = "${app.storage.cleanup.retry-interval:1m}")
    public void processDue() {
        int processed;
        do {
            List<UUID> due;
            try {
                due = tx.execute(s -> {
                    Instant now = clock.instant();
                    List<StorageDeletionTask> rows = tasks.lockDue(now, PageRequest.of(0, properties.batchSize()));
                    rows.forEach(t -> t.lease(now.plus(properties.lease())));
                    return rows.stream().map(StorageDeletionTask::getId).toList();
                });
            } catch (RuntimeException ex) {
                log.warn("Storage deletion queue unavailable: {}", ex.getClass().getSimpleName());
                return;
            }
            processed = due == null ? 0 : due.size();
            if (due != null) due.forEach(this::attempt);
        } while (processed == properties.batchSize());
    }

    /** One attempt outside any transaction; the outcome is recorded in its own short transaction. */
    void attempt(UUID id) {
        StorageDeletionTask task = newTx.execute(s -> tasks.findById(id).orElse(null));
        if (task == null || task.getStatus() != StorageDeletionTask.Status.PENDING) return;
        Instant now = clock.instant();
        Integer live;
        try {
            live = newTx.execute(s -> jdbc.queryForObject("SELECT count(*) FROM shipment_attachments WHERE file_key = ?", Integer.class, task.getFileKey()));
        } catch (RuntimeException ex) {
            log.warn("Storage deletion postponed, database check failed key={}", task.getFileKey());
            return;
        }
        if (live != null && live > 0) {
            record(id, t -> t.skip(now, "Object is still referenced by a photo"));
            log.warn("Storage deletion skipped, object still referenced key={}", task.getFileKey());
            return;
        }
        try {
            storage.delete(task.getFileKey());
            record(id, t -> t.done(clock.instant()));
            log.info("Storage object deleted key={} reason={}", task.getFileKey(), task.getReason());
        } catch (RuntimeException ex) {
            record(id, t -> t.failed(clock.instant(), ex.getClass().getSimpleName(), properties.maxAttempts(), properties.maxDelay()));
            log.warn("Storage delete failed, will retry key={} attempt={}", task.getFileKey(), task.getAttempts() + 1);
        }
    }

    /** Always a new transaction: this also runs from afterCommit, where joining the finished transaction would lose the write. */
    private void record(UUID id, java.util.function.Consumer<StorageDeletionTask> change) {
        try {
            newTx.executeWithoutResult(s -> tasks.findById(id).filter(t -> t.getStatus() == StorageDeletionTask.Status.PENDING).ifPresent(t -> {
                change.accept(t);
                if (t.getStatus() == StorageDeletionTask.Status.FAILED) log.error("Storage deletion gave up key={} attempts={}", t.getFileKey(), t.getAttempts());
            }));
        } catch (RuntimeException ex) {
            log.warn("Could not record storage deletion outcome id={}: {}", id, ex.getClass().getSimpleName());
        }
    }
}
