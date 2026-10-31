package com.truckgo.marketplace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.common.response.PageResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.MarketplaceShipmentSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Short-lived Redis cache of marketplace listing pages. Keys include a generation number; invalidation bumps the
 * generation after commit, so every cached page is dropped at once without scanning keys. Only the shared listing
 * is cached, never per-driver state. Redis failures fall back to PostgreSQL.
 */
@Component
public class MarketplaceCache {
    static final String GENERATION_KEY = "marketplace:v1:generation";
    private static final Logger log = LoggerFactory.getLogger(MarketplaceCache.class);
    private static final TypeReference<PageResponse<MarketplaceShipmentSummary>> TYPE = new TypeReference<>() { };

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MarketplaceProperties properties;

    public MarketplaceCache(StringRedisTemplate redis, ObjectMapper json, MarketplaceProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    public PageResponse<MarketplaceShipmentSummary> listing(MarketplaceFilter filter, Supplier<PageResponse<MarketplaceShipmentSummary>> loader) {
        Optional<String> key = key(filter);
        if (key.isPresent()) {
            try {
                String raw = redis.opsForValue().get(key.get());
                if (raw != null) return json.readValue(raw, TYPE);
            } catch (Exception ex) {
                log.warn("Marketplace cache read failed, using database: {}", ex.getClass().getSimpleName());
            }
        }
        PageResponse<MarketplaceShipmentSummary> page = loader.get();
        key.ifPresent(k -> {
            try { redis.opsForValue().set(k, json.writeValueAsString(page), properties.cacheTtl()); }
            catch (Exception ex) { log.warn("Marketplace cache write failed: {}", ex.getClass().getSimpleName()); }
        });
        return page;
    }

    /** Drops every cached page once the current transaction commits (immediately when there is none). */
    public void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate(); }
            });
        } else {
            invalidate();
        }
    }

    void invalidate() {
        try { redis.opsForValue().increment(GENERATION_KEY); }
        catch (RuntimeException ex) { log.error("Marketplace cache invalidation failed; pages expire within {}: {}", properties.cacheTtl(), ex.getClass().getSimpleName()); }
    }

    private Optional<String> key(MarketplaceFilter filter) {
        try {
            String generation = Optional.ofNullable(redis.opsForValue().get(GENERATION_KEY)).orElse("0");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(filter).getBytes(StandardCharsets.UTF_8));
            return Optional.of("marketplace:v1:" + generation + ":" + HexFormat.of().formatHex(digest, 0, 16));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        } catch (RuntimeException ex) {
            log.warn("Marketplace cache unavailable, using database: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
