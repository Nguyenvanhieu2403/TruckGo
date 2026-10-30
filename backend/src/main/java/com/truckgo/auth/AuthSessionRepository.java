package com.truckgo.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    /** Row lock serialises concurrent refresh requests that present the same token. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.tokenHash = :hash")
    Optional<AuthSession> findByTokenHashForUpdate(@Param("hash") String hash);

    Optional<AuthSession> findByTokenHash(String hash);

    List<AuthSession> findByFamilyId(UUID familyId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AuthSession s set s.revokedAt = :now, s.revokeReason = :reason where s.familyId = :familyId and s.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("reason") SessionRevokeReason reason, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AuthSession s set s.revokedAt = :now, s.revokeReason = :reason where s.userId = :userId and s.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("reason") SessionRevokeReason reason, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AuthSession s set s.revokedAt = :now, s.revokeReason = :reason where s.userId = :userId and s.familyId <> :keepFamilyId and s.revokedAt is null")
    int revokeAllForUserExceptFamily(@Param("userId") UUID userId, @Param("keepFamilyId") UUID keepFamilyId, @Param("reason") SessionRevokeReason reason, @Param("now") Instant now);
}
