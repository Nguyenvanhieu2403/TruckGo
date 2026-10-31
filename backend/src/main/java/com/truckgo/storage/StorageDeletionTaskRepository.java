package com.truckgo.storage;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StorageDeletionTaskRepository extends JpaRepository<StorageDeletionTask, UUID> {
    /** Due tasks, locked with SKIP LOCKED so several instances can work the queue without blocking each other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select t from StorageDeletionTask t where t.status = com.truckgo.storage.StorageDeletionTask.Status.PENDING and t.nextAttemptAt <= :now order by t.nextAttemptAt")
    List<StorageDeletionTask> lockDue(@Param("now") Instant now, Pageable page);

    @Query("select t.fileKey from StorageDeletionTask t where t.status = com.truckgo.storage.StorageDeletionTask.Status.PENDING and t.fileKey in :keys")
    List<String> pendingKeys(@Param("keys") Collection<String> keys);

    boolean existsByFileKeyAndStatus(String fileKey, StorageDeletionTask.Status status);

    List<StorageDeletionTask> findByShipmentIdOrderByCreatedAtAsc(UUID shipmentId);
}
