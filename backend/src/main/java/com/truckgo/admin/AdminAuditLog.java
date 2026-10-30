package com.truckgo.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_audit_logs")
public class AdminAuditLog {
    @Id private UUID id;
    @Column(name = "actor_id", nullable = false, updatable = false) private UUID actorId;
    @Column(nullable = false, updatable = false) private String action;
    @Column(name = "target_type", nullable = false, updatable = false) private String targetType;
    @Column(name = "target_id", nullable = false, updatable = false) private UUID targetId;
    @Column(updatable = false) private String details;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected AdminAuditLog() { }

    public AdminAuditLog(UUID actorId, String action, String targetType, UUID targetId, String details) {
        this.id = UUID.randomUUID();
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.details = details == null ? null : details.substring(0, Math.min(details.length(), 1000));
        this.createdAt = Instant.now();
    }

    public UUID getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public UUID getTargetId() { return targetId; }
    public String getDetails() { return details; }
}
