package com.truckgo.auth;

import com.truckgo.common.exception.TooManyRequestsException;
import com.truckgo.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class LoginAttemptServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SecurityProperties properties = new SecurityProperties(null, null,
            new SecurityProperties.LoginRateLimit(true, 5, 30, Duration.ofMinutes(15)), List.of(), 4);
    private final LoginAttemptService service = new LoginAttemptService(redis, properties);

    @Test void blocksIdentifierAfterTooManyFailures() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("5");
        when(redis.getExpire(anyString(), any(TimeUnit.class))).thenReturn(120L);
        assertThatThrownBy(() -> service.checkAllowed("key", "127.0.0.1")).isInstanceOf(TooManyRequestsException.class);
    }

    @Test void blocksIpAfterTooManyAttempts() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(31L);
        when(redis.getExpire(anyString(), any(TimeUnit.class))).thenReturn(30L);
        assertThatThrownBy(() -> service.checkAllowed("key", "127.0.0.1")).isInstanceOf(TooManyRequestsException.class);
    }

    @Test void failsOpenWhenRedisIsDown() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThatCode(() -> service.checkAllowed("key", "127.0.0.1")).doesNotThrowAnyException();
        assertThatCode(() -> service.recordFailure("key")).doesNotThrowAnyException();
        assertThatCode(() -> service.recordSuccess("key")).doesNotThrowAnyException();
    }
}
