package com.truckgo.pricing.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.pricing.engine.PricingRates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Redis cache of every active price table (rates + fees + period). Public reference data only; quotes are never
 * cached. Redis failures fall back to PostgreSQL; changes evict the entry after the transaction commits.
 */
@Component
public class ActivePricingCache {
    static final String KEY = "pricing:v1:active-configs";
    private static final Logger log = LoggerFactory.getLogger(ActivePricingCache.class);

    /** A cached price table with its effective period (filtered at use time, so future periods activate on time). */
    public record ActiveRates(PricingRates rates, Instant effectiveFrom, Instant effectiveTo) {
        public boolean effectiveAt(Instant at) { return !effectiveFrom.isAfter(at) && (effectiveTo == null || effectiveTo.isAfter(at)); }
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public ActivePricingCache(StringRedisTemplate redis, ObjectMapper json, @Value("${app.pricing.cache-ttl:10m}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    public List<ActiveRates> get(Supplier<List<ActiveRates>> loader) {
        Optional<List<ActiveRates>> cached = read();
        if (cached.isPresent()) return cached.get();
        List<ActiveRates> value = loader.get();
        try { redis.opsForValue().set(KEY, json.writeValueAsString(value), ttl); }
        catch (Exception ex) { log.warn("Pricing cache write failed: {}", ex.getClass().getSimpleName()); }
        return value;
    }

    public void evictAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { evict(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { evict(); }
        });
    }

    private void evict() {
        try { redis.delete(KEY); }
        catch (RuntimeException ex) { log.error("Pricing cache eviction failed; entry expires within {}: {}", ttl, ex.getClass().getSimpleName()); }
    }

    private Optional<List<ActiveRates>> read() {
        try {
            String raw = redis.opsForValue().get(KEY);
            return raw == null ? Optional.empty() : Optional.of(json.readValue(raw, new TypeReference<>() { }));
        } catch (Exception ex) {
            log.warn("Pricing cache read failed, using database: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
