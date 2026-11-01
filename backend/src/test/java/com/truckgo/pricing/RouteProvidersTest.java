package com.truckgo.pricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.truckgo.pricing.route.GuardedRouteDistanceService;
import com.truckgo.pricing.route.MapboxRouteDistanceService;
import com.truckgo.pricing.route.RouteCall;
import com.truckgo.pricing.route.RouteDistanceResult;
import com.truckgo.pricing.route.RouteDistanceService;
import com.truckgo.shipment.ShipmentStop;
import com.truckgo.shipment.StopType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RouteProvidersTest {
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    static ShipmentStop stop(int seq, String lat, String lng) {
        return new ShipmentStop(seq == 1 ? StopType.PICKUP : StopType.DELIVERY, seq, "A", "+84912345678", "Hà Nội", null, null, "x", "Số " + seq + ", Hà Nội",
                lat == null ? null : new BigDecimal(lat), lng == null ? null : new BigDecimal(lng), null);
    }

    private static final List<ShipmentStop> ROUTE = List.of(stop(1, "21.0285", "105.8542"), stop(2, "21.0400", "105.8000"), stop(3, "20.8449", "106.6881"));

    // ------------------------------------------------------------------ Mapbox

    @Test void mapboxRoutesThroughAllStopsAndNeverLogsTheToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<String> url = new AtomicReference<>();
        server.expect(r -> url.set(r.getURI().toString())).andRespond(withSuccess("""
                {"code":"Ok","routes":[{"distance":125400,"duration":7200,"legs":[{"distance":8000},{"distance":117400}]}]}""", MediaType.APPLICATION_JSON));
        RouteDistanceResult r = new MapboxRouteDistanceService(new RouteCall(builder.build(), 2, Duration.ZERO), "https://mapbox.example.test", "pk.secret",
                new BigDecimal("3000")).calculateDistance(ROUTE);
        assertThat(url.get()).contains("/directions/v5/mapbox/driving/105.8542,21.0285;105.8000,21.0400;106.6881,20.8449").contains("access_token=pk.secret");
        assertThat(r.available()).isTrue();
        assertThat(r.distanceKm()).isEqualByComparingTo("125.40");   // whole route, all legs
        assertThat(r.provider()).isEqualTo("mapbox");
    }

    @Test void mapboxWithoutCoordinatesOrWithNoRouteIsUnavailable() {
        MapboxRouteDistanceService m = new MapboxRouteDistanceService(new RouteCall(RestClient.builder().build(), 1, Duration.ZERO), "https://mapbox.example.test",
                "pk", new BigDecimal("3000"));
        assertThat(m.calculateDistance(List.of(stop(1, "21.0", "105.8"), stop(2, null, null))).reasonCode()).isEqualTo("MISSING_COORDINATES");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(r -> { }).andRespond(withSuccess("{\"code\":\"NoRoute\",\"routes\":[]}", MediaType.APPLICATION_JSON));
        RouteDistanceResult r = new MapboxRouteDistanceService(new RouteCall(builder.build(), 2, Duration.ZERO), "https://mapbox.example.test", "pk",
                new BigDecimal("3000")).calculateDistance(ROUTE);
        assertThat(r.available()).isFalse();
        assertThat(r.distanceKm()).isNull();
        assertThat(r.reasonCode()).isEqualTo("ROUTE_NOT_FOUND");
    }

    // ------------------------------------------------------------------ cache, quota, single flight

    /** A provider that counts calls and answers a fixed distance (or a failure). */
    static final class CountingProvider implements RouteDistanceService {
        final AtomicInteger calls = new AtomicInteger();
        volatile RouteDistanceResult answer = RouteDistanceResult.of(new BigDecimal("42.50"), 60, "stub", null);
        volatile long delayMs;
        @Override public String name() { return "stub"; }
        @Override public RouteDistanceResult calculateDistance(List<ShipmentStop> stops) {
            calls.incrementAndGet();
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return answer;
        }
    }

    /** In-memory stand-in for the few Redis operations the guard uses. */
    @SuppressWarnings("unchecked")
    static StringRedisTemplate fakeRedis(Map<String, String> store, boolean broken) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        if (broken) {
            when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
            return redis;
        }
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        doAnswer(i -> { store.put(i.getArgument(0), i.getArgument(1)); return null; }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(i -> store.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        when(ops.increment(anyString())).thenAnswer(i -> {
            String k = i.getArgument(0);
            long v = Long.parseLong(store.getOrDefault(k, "0")) + 1;
            store.put(k, String.valueOf(v));
            return v;
        });
        when(redis.delete(anyString())).thenAnswer(i -> store.remove(i.<String>getArgument(0)) != null);
        return redis;
    }

    private static GuardedRouteDistanceService guard(CountingProvider p, StringRedisTemplate redis, long perHour, String version) {
        return new GuardedRouteDistanceService(p, redis, JSON, new GuardedRouteDistanceService.Limits(Duration.ofDays(7), perHour, 0, Duration.ofMillis(300), version),
                Clock.fixed(Instant.parse("2026-11-01T03:00:00Z"), ZoneOffset.UTC), new SimpleMeterRegistry());
    }

    @Test void sameRouteIsServedFromCacheAndOtherRoutesAreNot() {
        CountingProvider p = new CountingProvider();
        Map<String, String> store = new HashMap<>();
        GuardedRouteDistanceService g = guard(p, fakeRedis(store, false), 0, "v1");
        assertThat(g.calculateDistance(ROUTE).distanceKm()).isEqualByComparingTo("42.50");   // miss
        assertThat(g.calculateDistance(ROUTE).distanceKm()).isEqualByComparingTo("42.50");   // hit
        assertThat(p.calls).hasValue(1);
        g.calculateDistance(List.of(ROUTE.get(0), ROUTE.get(2), ROUTE.get(1)));               // same stops, other order: other route
        g.calculateDistance(List.of(ROUTE.get(0), ROUTE.get(1)));
        assertThat(p.calls).hasValue(3);
        // A new configuration version starts a new key space.
        guard(p, fakeRedis(store, false), 0, "v2").calculateDistance(ROUTE);
        assertThat(p.calls).hasValue(4);
    }

    @Test void failuresAreNeverCached() {
        CountingProvider p = new CountingProvider();
        p.answer = RouteDistanceResult.unavailable("stub", "PROVIDER_ERROR", "down");
        GuardedRouteDistanceService g = guard(p, fakeRedis(new HashMap<>(), false), 0, "v1");
        g.calculateDistance(ROUTE);
        g.calculateDistance(ROUTE);
        assertThat(p.calls).hasValue(2);
    }

    @Test void quotaStopsProviderCallsAndSendsTheQuoteToReview() {
        CountingProvider p = new CountingProvider();
        GuardedRouteDistanceService g = guard(p, fakeRedis(new HashMap<>(), false), 2, "v1");
        g.calculateDistance(List.of(stop(1, "21.0", "105.0"), stop(2, "21.1", "105.1")));
        g.calculateDistance(List.of(stop(1, "21.0", "105.0"), stop(2, "21.2", "105.2")));
        RouteDistanceResult third = g.calculateDistance(List.of(stop(1, "21.0", "105.0"), stop(2, "21.3", "105.3")));
        assertThat(third.available()).isFalse();
        assertThat(third.reasonCode()).isEqualTo("PROVIDER_QUOTA_EXCEEDED");
        assertThat(third.distanceKm()).isNull();
        assertThat(p.calls).hasValue(2);
    }

    @Test void redisOutageStillAsksTheProvider() {
        CountingProvider p = new CountingProvider();
        GuardedRouteDistanceService g = guard(p, fakeRedis(null, true), 5, "v1");
        assertThat(g.calculateDistance(ROUTE).available()).isTrue();
        assertThat(g.calculateDistance(ROUTE).available()).isTrue();
        assertThat(p.calls).hasValue(2);   // no cache, but a working answer
    }

    @Test void concurrentRequestsForTheSameRouteCallTheProviderOnce() throws Exception {
        CountingProvider p = new CountingProvider();
        p.delayMs = 300;
        GuardedRouteDistanceService g = guard(p, fakeRedis(new java.util.concurrent.ConcurrentHashMap<>(), false), 0, "v1");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RouteDistanceResult>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) results.add(pool.submit(() -> { start.await(); return g.calculateDistance(ROUTE); }));
        start.countDown();
        for (Future<RouteDistanceResult> f : results) assertThat(f.get().distanceKm()).isEqualByComparingTo("42.50");
        pool.shutdown();
        assertThat(p.calls).hasValue(1);
    }
}
