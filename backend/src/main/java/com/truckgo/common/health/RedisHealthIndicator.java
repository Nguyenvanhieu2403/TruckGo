package com.truckgo.common.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisHealthIndicator implements HealthIndicator {
    private final StringRedisTemplate redis;
    public RedisHealthIndicator(StringRedisTemplate redis) { this.redis = redis; }
    @Override public Health health() {
        try { String result = redis.execute((RedisCallback<String>) RedisConnectionCommands::ping); return "PONG".equalsIgnoreCase(result) ? Health.up().build() : Health.down().withDetail("reason", "Unexpected Redis ping response").build(); }
        catch (Exception ex) { return Health.down(ex).build(); }
    }
}
