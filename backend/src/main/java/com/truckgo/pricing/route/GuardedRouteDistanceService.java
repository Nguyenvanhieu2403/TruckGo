package com.truckgo.pricing.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.shipment.ShipmentStop;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Wraps the configured route provider with:
 * <ul>
 *   <li><b>Cache</b> (Redis): key = provider + configuration version + the stops in order (coordinates, or the address
 *       when a stop has none), so two different routes never share an entry and changing provider settings starts a
 *       new key space. Only valid distances are cached, for app.maps.route-cache.ttl.</li>
 *   <li><b>Single flight</b>: concurrent requests for the same route in this instance share one provider call; across
 *       instances a short Redis lock makes the others wait for the first result.</li>
 *   <li><b>Quota</b>: provider calls per hour and per day (Redis counters, per-instance counters when Redis is down).
 *       Past the quota the answer is "unavailable" and the quote goes to admin review — never a guessed distance.</li>
 * </ul>
 * Redis only saves calls: every quote still stores the distance it was priced with in PostgreSQL.
 */
public class GuardedRouteDistanceService implements RouteDistanceService {
    private static final Logger log = LoggerFactory.getLogger(GuardedRouteDistanceService.class);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    public record Limits(Duration cacheTtl, long perHour, long perDay, Duration lockWait, String configVersion) { }

    private final RouteDistanceService delegate;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Limits limits;
    private final Clock clock;
    private final ConcurrentHashMap<String, CompletableFuture<RouteDistanceResult>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> localCounters = new ConcurrentHashMap<>();
    private final Counter hits, misses, calls, quotaRefusals;

    public GuardedRouteDistanceService(RouteDistanceService delegate, StringRedisTemplate redis, ObjectMapper json, Limits limits, Clock clock, MeterRegistry metrics) {
        this.delegate = delegate;
        this.redis = redis;
        this.json = json;
        this.limits = limits;
        this.clock = clock;
        String p = delegate.name();
        this.hits = Counter.builder("truckgo.route.cache").tag("provider", p).tag("result", "hit").register(metrics);
        this.misses = Counter.builder("truckgo.route.cache").tag("provider", p).tag("result", "miss").register(metrics);
        this.calls = Counter.builder("truckgo.route.provider.calls").tag("provider", p).register(metrics);
        this.quotaRefusals = Counter.builder("truckgo.route.quota.refused").tag("provider", p).register(metrics);
    }

    @Override public String name() { return delegate.name(); }

    @Override
    public RouteDistanceResult calculateDistance(List<ShipmentStop> stops) {
        String key = cacheKey(stops);
        RouteDistanceResult cached = readCache(key);
        if (cached != null) { hits.increment(); log.debug("Route cache hit provider={}", name()); return cached; }
        misses.increment();
        CompletableFuture<RouteDistanceResult> mine = new CompletableFuture<>();
        CompletableFuture<RouteDistanceResult> running = inFlight.putIfAbsent(key, mine);
        if (running != null) return running.join();   // same route already being computed in this instance
        try {
            RouteDistanceResult result = computeOnce(key, stops);
            mine.complete(result);
            return result;
        } catch (RuntimeException ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private RouteDistanceResult computeOnce(String key, List<ShipmentStop> stops) {
        String lockKey = key + ":lock";
        boolean locked = tryLock(lockKey);
        if (!locked) {
            // Another instance is asking the provider for this route: wait briefly for its cached answer.
            long deadline = System.currentTimeMillis() + limits.lockWait().toMillis();
            while (System.currentTimeMillis() < deadline) {
                sleep(200);
                RouteDistanceResult cached = readCache(key);
                if (cached != null) { hits.increment(); return cached; }
            }
        }
        try {
            if (!withinQuota()) {
                quotaRefusals.increment();
                log.warn("Route provider {} quota reached ({} per hour, {} per day); quote goes to admin review", name(), limits.perHour(), limits.perDay());
                return RouteDistanceResult.unavailable(name(), "PROVIDER_QUOTA_EXCEEDED",
                        "Đã đạt giới hạn tra cứu quãng đường của hệ thống; quản trị viên sẽ xác nhận quãng đường");
            }
            calls.increment();
            RouteDistanceResult result = delegate.calculateDistance(stops);
            log.info("Route provider {} called: available={} reason={}", name(), result.available(), result.reasonCode());
            if (isCacheable(result)) writeCache(key, result);
            return result;
        } finally {
            if (locked) unlock(lockKey);
        }
    }

    static boolean isCacheable(RouteDistanceResult r) {
        return r.available() && r.distanceKm() != null && r.distanceKm().signum() > 0;
    }

    /** provider + configuration version + ordered stops. Coordinates are rounded to 6 decimals (~0.1 m). */
    String cacheKey(List<ShipmentStop> stops) {
        String route = stops.stream().map(s -> s.getLatitude() != null && s.getLongitude() != null
                ? s.getLatitude().setScale(6, java.math.RoundingMode.HALF_UP).toPlainString() + "," + s.getLongitude().setScale(6, java.math.RoundingMode.HALF_UP).toPlainString()
                : "addr:" + s.getFullAddress().trim().toLowerCase()).collect(Collectors.joining("|"));
        return "route:v1:" + name() + ":" + limits.configVersion() + ":" + sha(route);
    }

    // ------------------------------------------------------------------ Redis helpers (all fail open)

    private RouteDistanceResult readCache(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) return null;
            RouteDistanceResult r = json.readValue(raw, RouteDistanceResult.class);
            return isCacheable(r) ? r : null;
        } catch (Exception ex) {
            log.warn("Route cache read failed, asking the provider: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private void writeCache(String key, RouteDistanceResult r) {
        try { redis.opsForValue().set(key, json.writeValueAsString(r), limits.cacheTtl()); }
        catch (Exception ex) { log.warn("Route cache write failed: {}", ex.getClass().getSimpleName()); }
    }

    private boolean tryLock(String key) {
        try { return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(30))); }
        catch (RuntimeException ex) { return true; }   // no Redis: the in-process single flight still applies
    }

    private void unlock(String key) {
        try { redis.delete(key); } catch (RuntimeException ignored) { /* expires by itself */ }
    }

    /** Counts this call against the hourly and daily limits (0 = unlimited). */
    private boolean withinQuota() {
        var now = clock.instant();
        return under("h:" + HOUR.format(now), limits.perHour(), Duration.ofHours(2)) && under("d:" + DAY.format(now), limits.perDay(), Duration.ofDays(2));
    }

    private boolean under(String window, long limit, Duration ttl) {
        if (limit <= 0) return true;
        String key = "route:quota:" + name() + ":" + window;
        long used;
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1) redis.expire(key, ttl);
            used = value == null ? 0 : value;
        } catch (RuntimeException ex) {
            used = localCounters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();   // per instance while Redis is down
        }
        return used <= limit;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

}
