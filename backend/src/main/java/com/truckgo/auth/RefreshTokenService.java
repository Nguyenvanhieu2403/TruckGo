package com.truckgo.auth;

import com.truckgo.common.exception.UnauthorizedException;
import com.truckgo.security.SecurityProperties;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Opaque refresh tokens with rotation and reuse detection.
 * <ul>
 *   <li>Each successful refresh revokes the presented token (ROTATED) and issues a new one in the same family.</li>
 *   <li>Presenting a ROTATED token within {@code reuse-grace} (two tabs refreshing at once) is rejected without side effects.</li>
 *   <li>Presenting it later is treated as theft: every live token in the family is revoked.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final String INVALID = "Phiên đăng nhập không hợp lệ hoặc đã hết hạn";

    private final AuthSessionRepository sessions;
    private final UserRepository users;
    private final SecurityProperties.Refresh config;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(AuthSessionRepository sessions, UserRepository users, SecurityProperties properties) {
        this.sessions = sessions;
        this.users = users;
        this.config = properties.refresh();
    }

    public record IssuedToken(String rawToken, UUID familyId, UUID sessionId) { }

    public record Rotation(User user, IssuedToken token) { }

    @Transactional
    public IssuedToken startSession(User user, String userAgent) {
        return issue(user.getId(), UUID.randomUUID(), userAgent);
    }

    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotation rotate(String rawToken, String userAgent) {
        if (rawToken == null || rawToken.isBlank()) throw new UnauthorizedException(INVALID);
        AuthSession current = sessions.findByTokenHashForUpdate(hash(rawToken)).orElseThrow(() -> new UnauthorizedException(INVALID));
        Instant now = Instant.now();
        if (current.isRevoked()) {
            boolean rotated = current.getRevokeReason() == SessionRevokeReason.ROTATED;
            if (rotated && now.isAfter(current.getRevokedAt().plus(config.reuseGrace()))) {
                int revoked = sessions.revokeFamily(current.getFamilyId(), SessionRevokeReason.REUSE_DETECTED, now);
                log.warn("Refresh token reuse detected; revoked session family userId={} familyId={} sessions={}", current.getUserId(), current.getFamilyId(), revoked);
            }
            throw new UnauthorizedException(INVALID);
        }
        if (!current.getExpiresAt().isAfter(now)) throw new UnauthorizedException(INVALID);
        User user = users.findById(current.getUserId()).orElseThrow(() -> new UnauthorizedException(INVALID));
        if (!user.isActive()) {
            sessions.revokeFamily(current.getFamilyId(), SessionRevokeReason.ACCOUNT_DISABLED, now);
            throw new UnauthorizedException(INVALID);
        }
        IssuedToken next = issue(user.getId(), current.getFamilyId(), userAgent);
        current.revoke(SessionRevokeReason.ROTATED, now);
        current.setLastUsedAt(now);
        current.setReplacedBySessionId(next.sessionId());
        return new Rotation(user, next);
    }

    /** Logout: revokes the whole family the token belongs to. Unknown tokens are ignored so logout is idempotent. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        sessions.findByTokenHash(hash(rawToken)).ifPresent(session -> sessions.revokeFamily(session.getFamilyId(), SessionRevokeReason.LOGOUT, Instant.now()));
    }

    @Transactional
    public int revokeAllForUser(UUID userId, SessionRevokeReason reason) {
        return sessions.revokeAllForUser(userId, reason, Instant.now());
    }

    @Transactional
    public int revokeOtherSessions(UUID userId, UUID keepFamilyId, SessionRevokeReason reason) {
        return sessions.revokeAllForUserExceptFamily(userId, keepFamilyId, reason, Instant.now());
    }

    private IssuedToken issue(UUID userId, UUID familyId, String userAgent) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String agent = userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 255));
        AuthSession session = sessions.saveAndFlush(new AuthSession(userId, familyId, hash(raw), Instant.now().plus(config.ttl()), agent));
        return new IssuedToken(raw, familyId, session.getId());
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
