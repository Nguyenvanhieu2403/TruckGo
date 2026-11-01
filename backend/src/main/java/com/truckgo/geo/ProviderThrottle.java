package com.truckgo.geo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Global request spacing for free public map services (Nominatim and the OSRM demo allow about one request per
 * second for the whole application, not per user). A Redis SET NX slot is shared by every backend instance; when
 * Redis is down an in-process lock keeps this instance within the limit. Waits briefly for a slot, otherwise
 * reports the provider as busy instead of breaking the usage policy.
 */
public class ProviderThrottle {
    private static final Logger log = LoggerFactory.getLogger(ProviderThrottle.class);
    private final StringRedisTemplate redis;
    private final String key;
    private final Duration spacing;
    private final Duration maxWait;
    private long localNext;

    public ProviderThrottle(StringRedisTemplate redis, String name, Duration spacing, Duration maxWait) {
        this.redis = redis;
        this.key = "maps:throttle:" + name;
        this.spacing = spacing;
        this.maxWait = maxWait;
    }

    /** True when a slot was obtained within maxWait. */
    public boolean acquire() {
        long deadline = System.currentTimeMillis() + maxWait.toMillis();
        while (true) {
            if (tryOnce()) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            try { Thread.sleep(150); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
        }
    }

    private boolean tryOnce() {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", spacing));
        } catch (RuntimeException ex) {
            log.debug("Throttle falls back to this instance: {}", ex.getClass().getSimpleName());
            synchronized (this) {
                long now = System.currentTimeMillis();
                if (now < localNext) return false;
                localNext = now + spacing.toMillis();
                return true;
            }
        }
    }
}
