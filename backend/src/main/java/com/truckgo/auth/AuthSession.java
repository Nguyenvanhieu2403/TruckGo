package com.truckgo.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One refresh token. Every rotation creates a new row in the same family; the presented token is stored only as a
 * SHA-256 hash.
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSession {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
    @Column(name = "family_id", nullable = false, updatable = false) private UUID familyId;
    @Column(name = "token_hash", nullable = false, updatable = false) private String tokenHash;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Enumerated(EnumType.STRING) @Column(name = "revoke_reason") private SessionRevokeReason revokeReason;
    @Column(name = "replaced_by_session_id") private UUID replacedBySessionId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "last_used_at") private Instant lastUsedAt;
    @Column(name = "user_agent") private String userAgent;

    protected AuthSession() { }

    public AuthSession(UUID userId, UUID familyId, String tokenHash, Instant expiresAt, String userAgent) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.createdAt = Instant.now();
    }

    public void revoke(SessionRevokeReason reason, Instant at) {
        if (revokedAt == null) { revokedAt = at; revokeReason = reason; }
    }

    public boolean isRevoked() { return revokedAt != null; }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public SessionRevokeReason getRevokeReason() { return revokeReason; }
    public UUID getReplacedBySessionId() { return replacedBySessionId; }
    public void setReplacedBySessionId(UUID replacedBySessionId) { this.replacedBySessionId = replacedBySessionId; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
