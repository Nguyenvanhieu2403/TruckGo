package com.truckgo.geo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Caches successful answers in Redis (keyed by a hash of the normalised query, so addresses are not readable from
 * keys). Failures are never cached, and any Redis error falls back to the provider.
 */
public class CachingGeocodingProvider implements GeocodingProvider {
    static final String PREFIX = "geo:v1:";
    private static final Logger log = LoggerFactory.getLogger(CachingGeocodingProvider.class);
    private static final TypeReference<List<GeoCandidate>> TYPE = new TypeReference<>() { };

    private final GeocodingProvider delegate;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final GeoProperties properties;

    public CachingGeocodingProvider(GeocodingProvider delegate, StringRedisTemplate redis, ObjectMapper json, GeoProperties properties) {
        this.delegate = delegate;
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    @Override public String name() { return delegate.name(); }
    @Override public boolean enabled() { return delegate.enabled(); }

    @Override
    public List<GeoCandidate> search(String query, int limit) {
        String key = PREFIX + delegate.name() + ":" + hash(query);
        try {
            String raw = redis.opsForValue().get(key);
            if (raw != null) return json.readValue(raw, TYPE).stream().limit(limit).toList();
        } catch (Exception ex) {
            log.warn("Geocoding cache read failed: {}", ex.getClass().getSimpleName());
        }
        // Cache the provider's full answer so a later call with a larger limit still gets every candidate.
        List<GeoCandidate> results = delegate.search(query, properties.maxResults());
        try { redis.opsForValue().set(key, json.writeValueAsString(results), properties.cacheTtl()); }
        catch (Exception ex) { log.warn("Geocoding cache write failed: {}", ex.getClass().getSimpleName()); }
        return results.stream().limit(limit).toList();
    }

    static String hash(String query) {
        String normal = Normalizer.normalize(query.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFC).replaceAll("\\s+", " ");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normal.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
