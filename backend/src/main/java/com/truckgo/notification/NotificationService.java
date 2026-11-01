package com.truckgo.notification;

import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * In-app notifications stored in PostgreSQL (the record of truth). {@link #notify} joins the caller's transaction, so a
 * notification exists exactly when the business change that caused it was committed; it is then pushed to the
 * recipient's open streams by {@link NotificationStreamHub} (after commit, through Redis Pub/Sub). A stream that was
 * down replays what it missed from PostgreSQL, so the push is an optimisation, never the only copy.
 *
 * <p>Idempotency: a caller passes an event key identifying the business event (e.g. "APPLICATION_ACCEPTED:&lt;id&gt;");
 * the same event for the same recipient is stored once, so a retried event never notifies twice.
 */
@Service
public class NotificationService {
    public record NotificationResponse(UUID id, NotificationType type, String title, String content, String referenceType, UUID referenceId,
                                       boolean read, Instant readAt, Instant createdAt) {
        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getContent(), n.getReferenceType(), n.getReferenceId(), n.isRead(), n.getReadAt(), n.getCreatedAt());
        }
    }

    private final NotificationRepository notifications;
    private final JdbcTemplate jdbc;
    private final NotificationStreamHub hub;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, JdbcTemplate jdbc, NotificationStreamHub hub, Clock clock) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.hub = hub;
        this.clock = clock;
    }

    @Transactional
    public void notify(UUID recipientUserId, NotificationType type, String title, String content, String referenceType, UUID referenceId) {
        notify(recipientUserId, type, title, content, referenceType, referenceId, null);
    }

    /** Stores and pushes one notification; empty when eventKey was already delivered to this recipient. */
    @Transactional
    public Optional<UUID> notify(UUID recipientUserId, NotificationType type, String title, String content, String referenceType, UUID referenceId,
                                 String eventKey) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String text = content.length() > 1000 ? content.substring(0, 1000) : content;
        String key = eventKey == null ? null : eventKey.length() > 200 ? eventKey.substring(0, 200) : eventKey;
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO notifications (id, recipient_user_id, notification_type, title, content, reference_type, reference_id, is_read, created_at, event_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?)
                ON CONFLICT (recipient_user_id, event_key) WHERE event_key IS NOT NULL DO NOTHING
                RETURNING id""", UUID.class, id, recipientUserId, type.name(), title, text, referenceType, referenceId, Timestamp.from(now), key);
        if (inserted.isEmpty()) return Optional.empty();
        hub.publishAfterCommit(recipientUserId, new NotificationResponse(id, type, title, text, referenceType, referenceId, false, null, now));
        return Optional.of(id);
    }

    /** Notifications of this user created after the given one (oldest first), for a stream that reconnects. */
    @Transactional(readOnly = true)
    public List<NotificationResponse> since(UUID userId, UUID lastSeenId, int limit) {
        return jdbc.query("""
                SELECT n.* FROM notifications n, notifications last
                 WHERE last.id = ? AND last.recipient_user_id = ? AND n.recipient_user_id = ?
                   AND (n.created_at, n.id) > (last.created_at, last.id)
                 ORDER BY n.created_at, n.id LIMIT ?""",
                (rs, i) -> new NotificationResponse(rs.getObject("id", UUID.class), NotificationType.valueOf(rs.getString("notification_type")),
                        rs.getString("title"), rs.getString("content"), rs.getString("reference_type"), rs.getObject("reference_id", UUID.class),
                        rs.getBoolean("is_read"), rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()), lastSeenId, userId, userId, limit);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UUID userId, boolean unreadOnly, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(unreadOnly ? notifications.findByRecipientUserIdAndReadFalse(userId, pageable) : notifications.findByRecipientUserId(userId, pageable),
                NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) { return notifications.countByRecipientUserIdAndReadFalse(userId); }

    /** Only the recipient can read a notification; anyone else gets 404. */
    @Transactional
    public NotificationResponse markRead(UUID userId, UUID notificationId) {
        Notification n = notifications.findByIdAndRecipientUserId(notificationId, userId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông báo"));
        n.markRead(clock.instant());
        return NotificationResponse.from(n);
    }

    @Transactional
    public int markAllRead(UUID userId) { return notifications.markAllRead(userId, clock.instant()); }
}
