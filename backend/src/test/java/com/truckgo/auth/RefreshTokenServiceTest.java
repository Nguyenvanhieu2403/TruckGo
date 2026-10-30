package com.truckgo.auth;

import com.truckgo.common.exception.UnauthorizedException;
import com.truckgo.security.SecurityProperties;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import com.truckgo.user.UserRole;
import com.truckgo.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {
    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SecurityProperties properties = new SecurityProperties(null,
            new SecurityProperties.Refresh(Duration.ofDays(7), Duration.ofSeconds(10), "tg_refresh", "/api/v1/auth", true, "Strict"), null, List.of(), 4);
    private final RefreshTokenService service = new RefreshTokenService(sessions, users, properties);
    private final User user = new User("Nguyen Van A", "a@example.com", null, "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);

    @BeforeEach void setUp() {
        when(sessions.saveAndFlush(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test void storesOnlyTheHashOfTheToken() {
        var issued = service.startSession(user, "JUnit");
        ArgumentCaptor<AuthSession> saved = ArgumentCaptor.forClass(AuthSession.class);
        verify(sessions).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTokenHash()).hasSize(64).isNotEqualTo(issued.rawToken()).isEqualTo(RefreshTokenService.hash(issued.rawToken()));
    }

    @Test void validTokenIsRotated() {
        AuthSession current = session(Instant.now().plusSeconds(3600));
        when(sessions.findByTokenHashForUpdate(current.getTokenHash())).thenReturn(Optional.of(current));
        var rotation = service.rotate("raw-token", "JUnit");
        assertThat(current.getRevokeReason()).isEqualTo(SessionRevokeReason.ROTATED);
        assertThat(current.getReplacedBySessionId()).isEqualTo(rotation.token().sessionId());
        assertThat(rotation.token().rawToken()).isNotEqualTo("raw-token");
        assertThat(rotation.token().familyId()).isEqualTo(current.getFamilyId());
    }

    @Test void revokedTokenCannotBeReused() {
        AuthSession current = session(Instant.now().plusSeconds(3600));
        current.revoke(SessionRevokeReason.LOGOUT, Instant.now());
        when(sessions.findByTokenHashForUpdate(current.getTokenHash())).thenReturn(Optional.of(current));
        assertThatThrownBy(() -> service.rotate("raw-token", null)).isInstanceOf(UnauthorizedException.class);
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test void reuseOfRotatedTokenAfterGraceRevokesFamily() {
        AuthSession current = session(Instant.now().plusSeconds(3600));
        current.revoke(SessionRevokeReason.ROTATED, Instant.now().minusSeconds(60));
        when(sessions.findByTokenHashForUpdate(current.getTokenHash())).thenReturn(Optional.of(current));
        assertThatThrownBy(() -> service.rotate("raw-token", null)).isInstanceOf(UnauthorizedException.class);
        verify(sessions).revokeFamily(eq(current.getFamilyId()), eq(SessionRevokeReason.REUSE_DETECTED), any());
    }

    @Test void concurrentReuseWithinGraceIsRejectedWithoutRevokingFamily() {
        AuthSession current = session(Instant.now().plusSeconds(3600));
        current.revoke(SessionRevokeReason.ROTATED, Instant.now());
        when(sessions.findByTokenHashForUpdate(current.getTokenHash())).thenReturn(Optional.of(current));
        assertThatThrownBy(() -> service.rotate("raw-token", null)).isInstanceOf(UnauthorizedException.class);
        verify(sessions, never()).revokeFamily(any(), any(), any());
    }

    @Test void expiredOrUnknownTokenIsRejected() {
        AuthSession expired = session(Instant.now().minusSeconds(1));
        when(sessions.findByTokenHashForUpdate(expired.getTokenHash())).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.rotate("raw-token", null)).isInstanceOf(UnauthorizedException.class);
        when(sessions.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rotate("other", null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test void inactiveUserCannotRefresh() {
        user.setStatus(UserStatus.LOCKED);
        AuthSession current = session(Instant.now().plusSeconds(3600));
        when(sessions.findByTokenHashForUpdate(current.getTokenHash())).thenReturn(Optional.of(current));
        assertThatThrownBy(() -> service.rotate("raw-token", null)).isInstanceOf(UnauthorizedException.class);
        verify(sessions).revokeFamily(eq(current.getFamilyId()), eq(SessionRevokeReason.ACCOUNT_DISABLED), any());
    }

    @Test void logoutRevokesFamily() {
        AuthSession current = session(Instant.now().plusSeconds(3600));
        when(sessions.findByTokenHash(current.getTokenHash())).thenReturn(Optional.of(current));
        service.revoke("raw-token");
        verify(sessions).revokeFamily(eq(current.getFamilyId()), eq(SessionRevokeReason.LOGOUT), any());
    }

    private AuthSession session(Instant expiresAt) {
        return new AuthSession(user.getId(), java.util.UUID.randomUUID(), RefreshTokenService.hash("raw-token"), expiresAt, null);
    }
}
