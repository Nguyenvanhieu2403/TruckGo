package com.truckgo.notification;

import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * In-app notifications stored in PostgreSQL. {@link #notify} joins the caller's transaction, so a notification exists
 * exactly when the business change that caused it was committed. Push channels (WebSocket/SSE/mobile) can be added
 * later by listening for new rows without changing the callers.
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
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public void notify(UUID recipientUserId, NotificationType type, String title, String content, String referenceType, UUID referenceId) {
        notifications.save(new Notification(recipientUserId, type, title, content, referenceType, referenceId));
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
