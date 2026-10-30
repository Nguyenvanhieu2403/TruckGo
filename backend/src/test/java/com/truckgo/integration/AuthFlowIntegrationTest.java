package com.truckgo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.truckgo.auth.AuthSessionRepository;
import com.truckgo.auth.RefreshTokenService;
import com.truckgo.auth.SessionRevokeReason;
import com.truckgo.common.exception.UnauthorizedException;
import com.truckgo.user.User;
import com.truckgo.user.UserRole;
import com.truckgo.user.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
class AuthFlowIntegrationTest extends IntegrationTestSupport {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired JdbcTemplate jdbc;
    @Autowired AuthSessionRepository sessions;
    @Autowired RefreshTokenService refreshTokens;

    @AfterEach void dropFaultTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_profile_insert ON driver_profiles");
    }

    @Test void flywayAppliedSprintOneAndSprintTwoMigrations() {
        List<String> versions = jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2");
    }

    @Test void databaseEnforcesUniqueAndContactConstraints() {
        String email = uniqueEmail();
        String insert = "insert into users (id, email, phone, password_hash, full_name, role, status, created_at, updated_at) values (?, ?, ?, 'x', 'Test', 'CUSTOMER', 'ACTIVE', now(), now())";
        jdbc.update(insert, UUID.randomUUID(), email, null);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), email, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), null, "0912345678")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void customerRegistrationIgnoresClientSuppliedRoleAndHidesPassword() throws Exception {
        String email = uniqueEmail();
        var result = postJson("/api/v1/auth/register/customer", Map.of("fullName", "Nguyen Van A", "email", email, "password", PASSWORD,
                "confirmPassword", PASSWORD, "role", "ADMIN", "status", "ACTIVE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.user.role").value("CUSTOMER"))
                .andReturn();
        String raw = result.getResponse().getContentAsString();
        assertThat(raw).doesNotContain(PASSWORD).doesNotContainIgnoringCase("passwordHash").doesNotContain("password_hash");
        User stored = users.findByEmail(email).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(stored.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
    }

    @Test void duplicateContactIsRejectedWith409() throws Exception {
        String email = uniqueEmail();
        String phone = uniquePhone();
        registerCustomer(email, phone);
        postJson("/api/v1/auth/register/customer", Map.of("fullName", "Other", "email", email.toUpperCase(), "password", PASSWORD, "confirmPassword", PASSWORD))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.email").exists());
        postJson("/api/v1/auth/register/customer", Map.of("fullName", "Other", "phone", "+84" + phone.substring(1), "password", PASSWORD, "confirmPassword", PASSWORD))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.phone").exists());
    }

    @Test void registrationValidatesContactAndPasswordConfirmation() throws Exception {
        postJson("/api/v1/auth/register/customer", Map.of("fullName", "Nguyen Van A", "password", PASSWORD, "confirmPassword", "different-123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.contactProvided").exists())
                .andExpect(jsonPath("$.data.confirmPasswordMatching").exists());
        postJson("/api/v1/auth/register/customer", Map.of("fullName", "Nguyen Van A", "email", uniqueEmail(), "password", "weak", "confirmPassword", "weak"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.password").exists());
    }

    @Test void driverRegistrationCreatesUnapprovedProfile() throws Exception {
        JsonNode created = registerDriver(uniqueEmail());
        assertThat(created.at("/data/user/role").asText()).isEqualTo("DRIVER");
        assertThat(created.at("/data/driverVerificationStatus").asText()).isEqualTo("NOT_SUBMITTED");
        UUID userId = UUID.fromString(created.at("/data/user/id").asText());
        assertThat(jdbc.queryForObject("select verification_status from driver_profiles where user_id = ?", String.class, userId)).isEqualTo("NOT_SUBMITTED");
    }

    @Test void failedProfileInsertRollsBackTheAccount() throws Exception {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_profile_insert() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'simulated failure'; END; $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER fail_profile_insert BEFORE INSERT ON driver_profiles FOR EACH ROW EXECUTE FUNCTION fail_profile_insert()");
        String email = uniqueEmail();
        postJson("/api/v1/auth/register/driver", Map.of("fullName", "Nguyen Van B", "email", email, "password", PASSWORD,
                "confirmPassword", PASSWORD, "licenseNumber", uniqueLicense(), "licenseClass", "C"))
                .andExpect(status().is5xxServerError());
        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test void loginWorksWithEmailAndPhoneAndSetsSecureHttpOnlyCookie() throws Exception {
        String email = uniqueEmail();
        String phone = uniquePhone();
        registerCustomer(email, phone);
        var result = postJson("/api/v1/auth/login", Map.of("identifier", email, "password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).contains("tg_refresh=").contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/api/v1/auth");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(refreshCookie(result));

        Session byPhone = login(phone.replaceFirst("^0", "+84 "), PASSWORD);
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(byPhone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test void invalidCredentialsReturnTheSame401() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        String wrongPassword = body(postJson("/api/v1/auth/login", Map.of("identifier", email, "password", "Wrong-Password-1"))
                .andExpect(status().isUnauthorized()).andReturn()).get("message").asText();
        String unknownUser = body(postJson("/api/v1/auth/login", Map.of("identifier", uniqueEmail(), "password", PASSWORD))
                .andExpect(status().isUnauthorized()).andReturn()).get("message").asText();
        assertThat(wrongPassword).isEqualTo(unknownUser);
    }

    @Test void repeatedFailuresAreThrottledInRedis() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        for (int i = 0; i < 5; i++) postJson("/api/v1/auth/login", Map.of("identifier", email, "password", "Wrong-Password-1")).andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/login", Map.of("identifier", email, "password", PASSWORD))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test void lockedAccountCannotLoginAndItsTokensStopWorking() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        Session session = login(email, PASSWORD);
        User user = users.findByEmail(email).orElseThrow();
        user.setStatus(UserStatus.LOCKED);
        users.save(user);
        postJson("/api/v1/auth/login", Map.of("identifier", email, "password", PASSWORD)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(session))).andExpect(status().isUnauthorized());
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test void refreshRotatesTokenAndDetectsReuse() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        Session first = login(email, PASSWORD);

        Session second = session(refresh(first.refreshToken()).andExpect(status().isOk()).andReturn());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());

        Thread.sleep(700); // past the 500ms reuse grace configured for tests
        refresh(first.refreshToken()).andExpect(status().isUnauthorized());
        // Reuse revoked the whole family, including the token issued by the legitimate rotation.
        refresh(second.refreshToken()).andExpect(status().isUnauthorized());
        assertThat(sessions.findByTokenHash(sha256(second.refreshToken())).orElseThrow().getRevokeReason()).isEqualTo(SessionRevokeReason.REUSE_DETECTED);
    }

    @Test void concurrentRefreshWithSameTokenRotatesExactlyOnce() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        Session session = login(email, PASSWORD);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Boolean> task = () -> {
                start.await();
                try { refreshTokens.rotate(session.refreshToken(), null); return true; }
                catch (UnauthorizedException ex) { return false; }
            };
            outcomes.add(pool.submit(task));
        }
        start.countDown();
        long successes = 0;
        for (Future<Boolean> outcome : outcomes) if (outcome.get()) successes++;
        pool.shutdown();
        assertThat(successes).isEqualTo(1);
        UUID family = sessions.findByTokenHash(sha256(session.refreshToken())).orElseThrow().getFamilyId();
        // The losers arrived within the grace window, so the family (and the winner's new token) stays valid.
        assertThat(sessions.findByFamilyId(family)).hasSize(2).filteredOn(s -> !s.isRevoked()).hasSize(1);
    }

    @Test void refreshRequiresCsrfHeaderAndAllowedOrigin() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        Session session = login(email, PASSWORD);
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("tg_refresh", session.refreshToken()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("tg_refresh", session.refreshToken()))
                .header("X-Requested-With", "XMLHttpRequest").header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        refresh(null).andExpect(status().isUnauthorized());
        refresh(session.refreshToken()).andExpect(status().isOk());
    }

    @Test void logoutRevokesSessionAndClearsCookie() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        Session session = login(email, PASSWORD);
        var result = mvc.perform(post("/api/v1/auth/logout").header("X-Requested-With", "XMLHttpRequest").cookie(new Cookie("tg_refresh", session.refreshToken())))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("tg_refresh=").contains("Max-Age=0");
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
        assertThat(sessions.findByTokenHash(sha256(session.refreshToken())).orElseThrow().getRevokeReason()).isEqualTo(SessionRevokeReason.LOGOUT);
    }

    @Test void changePasswordRevokesOtherSessionsOnly() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        Session current = login(email, PASSWORD);
        Session other = login(email, PASSWORD);
        String newPassword = "New-Example-456";
        postJsonAuthorized("/api/v1/users/me/change-password", current, Map.of("currentPassword", "Wrong-Password-1", "newPassword", newPassword, "confirmNewPassword", newPassword))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.currentPassword").exists());
        postJsonAuthorized("/api/v1/users/me/change-password", current, Map.of("currentPassword", PASSWORD, "newPassword", newPassword, "confirmNewPassword", newPassword))
                .andExpect(status().isOk());
        refresh(other.refreshToken()).andExpect(status().isUnauthorized());
        refresh(current.refreshToken()).andExpect(status().isOk());
        postJson("/api/v1/auth/login", Map.of("identifier", email, "password", PASSWORD)).andExpect(status().isUnauthorized());
        login(email, newPassword);
    }

    @Test void profileUpdateCannotChangeRoleOrStatus() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        Session session = login(email, PASSWORD);
        String newPhone = uniquePhone();
        mvc.perform(patch("/api/v1/users/me").header("Authorization", bearer(session)).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("fullName", "Tran Thi C", "phone", newPhone, "role", "ADMIN", "status", "ACTIVE", "phoneVerified", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Tran Thi C"))
                .andExpect(jsonPath("$.data.phone").value("+84" + newPhone.substring(1)))
                .andExpect(jsonPath("$.data.phoneVerified").value(false))
                .andExpect(jsonPath("$.data.role").value("CUSTOMER"));
    }

    @Test void protectedEndpointsReturn401WithoutTokenAnd401ForForgedToken() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        String forged = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDAiLCJyb2xlcyI6WyJBRE1JTiJdfQ.c2lnbmF0dXJl";
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
    }

    @Test void corsAllowsOnlyConfiguredOriginWithCredentials() throws Exception {
        mvc.perform(options("/api/v1/auth/refresh").header("Origin", "http://localhost:4200").header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "x-requested-with"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options("/api/v1/auth/refresh").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test void expiredSessionRowIsRejected() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        Session session = login(email, PASSWORD);
        jdbc.update("update auth_sessions set expires_at = ? where token_hash = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), sha256(session.refreshToken()));
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions postJsonAuthorized(String url, Session session, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", bearer(session)).contentType("application/json").content(json.writeValueAsString(body)));
    }

    private static String sha256(String raw) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
