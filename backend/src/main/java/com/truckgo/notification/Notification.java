package com.truckgo.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {
    @Id private UUID id;
    @Column(name = "recipient_user_id", nullable = false, updatable = false) private UUID recipientUserId;
    @Enumerated(EnumType.STRING) @Column(name = "notification_type", nullable = false, updatable = false) private NotificationType type;
    @Column(nullable = false, updatable = false) private String title;
    @Column(nullable = false, updatable = false) private String content;
    @Column(name = "reference_type", updatable = false) private String referenceType;
    @Column(name = "reference_id", updatable = false) private UUID referenceId;
    @Column(name = "is_read", nullable = false) private boolean read;
    @Column(name = "read_at") private Instant readAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected Notification() { }

    public Notification(UUID recipientUserId, NotificationType type, String title, String content, String referenceType, UUID referenceId) {
        this.id = UUID.randomUUID();
        this.recipientUserId = recipientUserId;
        this.type = type;
        this.title = title;
        this.content = content.length() > 1000 ? content.substring(0, 1000) : content;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.createdAt = Instant.now();
    }

    public void markRead(Instant at) { if (!read) { read = true; readAt = at; } }

    public UUID getId() { return id; }
    public UUID getRecipientUserId() { return recipientUserId; }
    public NotificationType getType() { return type; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getReferenceType() { return referenceType; }
    public UUID getReferenceId() { return referenceId; }
    public boolean isRead() { return read; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
}
