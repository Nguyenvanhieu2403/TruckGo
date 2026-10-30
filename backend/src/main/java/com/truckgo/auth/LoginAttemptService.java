package com.truckgo.auth;

import com.truckgo.common.exception.TooManyRequestsException;
import com.truckgo.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Login throttling in Redis with fixed windows:
 * <ul>
 *   <li>failed attempts per identifier (keyed by a hash, so no e-mail/phone is stored in Redis);</li>
 *   <li>all attempts per client IP.</li>
 * </ul>
 * Counters are incremented with a Lua script (INCR + PEXPIRE on first hit) so they are atomic. If Redis is unavailable
 * the limiter fails open and logs a warning: users are never locked out because of an infrastructure fault, and
 * BCrypt still bounds the guessing rate.
 */
@Service
public class LoginAttemptService {
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    private static final String MESSAGE = "Bạn đã thử đăng nhập quá nhiều lần. Vui lòng thử lại sau.";
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end return c", Long.class);

    private final StringRedisTemplate redis;
    private final SecurityProperties.LoginRateLimit config;

    public LoginAttemptService(StringRedisTemplate redis, SecurityProperties properties) {
        this.redis = redis;
        this.config = properties.loginRateLimit();
    }

    /** Counts this attempt against the IP and rejects if either the IP or the identifier is over its limit. */
    public void checkAllowed(String identifierKey, String clientIp) {
        if (!config.enabled()) return;
        try {
            String ipKey = "auth:login:ip:" + clientIp;
            Long ipAttempts = redis.execute(INCREMENT, List.of(ipKey), String.valueOf(config.window().toMillis()));
            if (ipAttempts != null && ipAttempts > config.maxAttemptsPerIp()) throw tooMany(ipKey);
            String failures = redis.opsForValue().get(failureKey(identifierKey));
            if (failures != null && Long.parseLong(failures) >= config.maxFailuresPerIdentifier()) throw tooMany(failureKey(identifierKey));
        } catch (TooManyRequestsException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Login rate limiter unavailable, allowing attempt: {}", ex.getClass().getSimpleName());
        }
    }

    public void recordFailure(String identifierKey) {
        if (!config.enabled()) return;
        try { redis.execute(INCREMENT, List.of(failureKey(identifierKey)), String.valueOf(config.window().toMillis())); }
        catch (RuntimeException ex) { log.warn("Login rate limiter unavailable, failure not recorded: {}", ex.getClass().getSimpleName()); }
    }

    public void recordSuccess(String identifierKey) {
        if (!config.enabled()) return;
        try { redis.delete(failureKey(identifierKey)); }
        catch (RuntimeException ex) { log.warn("Login rate limiter unavailable, counter not cleared: {}", ex.getClass().getSimpleName()); }
    }

    private TooManyRequestsException tooMany(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        return new TooManyRequestsException(MESSAGE, ttl == null || ttl < 1 ? config.window().toSeconds() : ttl);
    }

    private static String failureKey(String identifierKey) { return "auth:login:fail:" + identifierKey; }
}
