package com.truckgo.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Page<Notification> findByRecipientUserId(UUID recipientUserId, Pageable pageable);
    Page<Notification> findByRecipientUserIdAndReadFalse(UUID recipientUserId, Pageable pageable);
    long countByRecipientUserIdAndReadFalse(UUID recipientUserId);
    Optional<Notification> findByIdAndRecipientUserId(UUID id, UUID recipientUserId);
    List<Notification> findByReferenceIdOrderByCreatedAtAsc(UUID referenceId);

    /** Replaces the text of notifications of one type about one object, e.g. to remove a used one-time code. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE notifications SET content = :content WHERE notification_type = :type AND reference_id = :referenceId AND content <> :content", nativeQuery = true)
    int redact(@Param("type") String type, @Param("referenceId") UUID referenceId, @Param("content") String content);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.read = true, n.readAt = :now where n.recipientUserId = :userId and n.read = false")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);
}
