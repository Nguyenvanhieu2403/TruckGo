package com.truckgo.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Read-through JSON cache for catalogs in Redis. Only public, rarely changing reference data is cached, never
 * orders. Any Redis failure is logged and the value is loaded from PostgreSQL, so the cache can never block or
 * corrupt order processing.
 */
@Component
public class CatalogCache {
    static final String PREFIX = "catalog:v1:";
    static final List<String> NAMES = List.of("item-categories", "vehicle-types", "vehicle-body-types", "shipment-services");
    private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public CatalogCache(StringRedisTemplate redis, ObjectMapper json, @Value("${app.catalog.cache-ttl:1h}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    public <T> List<T> get(String name, TypeReference<List<T>> type, Supplier<List<T>> loader) {
        String key = PREFIX + name;
        Optional<List<T>> cached = read(key, type);
        if (cached.isPresent()) return cached.get();
        List<T> value = loader.get();
        write(key, value);
        return value;
    }

    public void evictAll() {
        try {
            redis.delete(NAMES.stream().map(n -> PREFIX + n).toList());
        } catch (RuntimeException ex) {
            // Entries still expire after the TTL; log loudly so operators know the cache may be stale until then.
            log.error("Catalog cache eviction failed; entries expire within {}: {}", ttl, ex.getClass().getSimpleName());
        }
    }

    private <T> Optional<List<T>> read(String key, TypeReference<List<T>> type) {
        try {
            String raw = redis.opsForValue().get(key);
            return raw == null ? Optional.empty() : Optional.of(json.readValue(raw, type));
        } catch (Exception ex) {
            log.warn("Catalog cache read failed for {}, using database: {}", key, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void write(String key, Object value) {
        try { redis.opsForValue().set(key, json.writeValueAsString(value), ttl); }
        catch (Exception ex) { log.warn("Catalog cache write failed for {}: {}", key, ex.getClass().getSimpleName()); }
    }
}
