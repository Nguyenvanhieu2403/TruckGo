package com.truckgo.pricing.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.geo.GeoConfig;
import com.truckgo.geo.OsmProperties;
import com.truckgo.geo.ProviderThrottle;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Locale;

@Configuration
public class RouteDistanceConfig {
    private static final Logger log = LoggerFactory.getLogger(RouteDistanceConfig.class);

    /** MAPS_ROUTE_PROVIDER picks exactly one provider; a misconfigured one fails at startup instead of silently switching. */
    @Bean
    RouteDistanceService routeDistanceService(RouteProperties properties, OsmProperties osm, StringRedisTemplate redis, ObjectMapper json, Clock clock,
                                              MeterRegistry metrics) {
        String provider = properties.routeProvider() == null ? "none" : properties.routeProvider().trim().toLowerCase(Locale.ROOT);
        RouteDistanceService service = switch (provider) {
            case "none", "manual" -> {
                log.info("No route provider configured: quotes wait for an admin-verified distance");
                yield null;
            }
            case "google" -> {
                RouteProperties.Google google = properties.google();
                if (google == null || isBlank(google.apiKey())) throw new IllegalStateException("MAPS_ROUTE_PROVIDER=google requires GOOGLE_MAPS_API_KEY");
                log.info("Route provider: Google Directions");
                yield new GoogleRouteDistanceService(call(client(properties, null), properties), properties);
            }
            case "mapbox" -> {
                RouteProperties.Mapbox mapbox = properties.mapbox();
                if (mapbox == null || isBlank(mapbox.accessToken())) throw new IllegalStateException("MAPS_ROUTE_PROVIDER=mapbox requires MAPBOX_ACCESS_TOKEN");
                log.info("Route provider: Mapbox Directions at {}", mapbox.baseUrl());
                yield new MapboxRouteDistanceService(call(client(properties, null), properties), mapbox.baseUrl(), mapbox.accessToken(), properties.maxReasonableKm());
            }
            case "osrm" -> {
                log.info("Route provider: OSRM at {} (stops need coordinates)", osm.osrmUrl());
                yield new OsrmRouteDistanceService(call(GeoConfig.osmClient(osm, properties), properties), osm.osrmUrl(), properties.maxReasonableKm(),
                        new ProviderThrottle(redis, "osrm", osm.spacing(), osm.maxWait()));
            }
            default -> throw new IllegalStateException("Unsupported MAPS_ROUTE_PROVIDER: " + provider);
        };
        if (service == null) return new ManualRouteDistanceService();
        RouteProperties.RouteCache cache = properties.routeCache();
        // The provider's endpoint is part of the cache version, so pointing it elsewhere never reuses old routes.
        String endpoint = switch (provider) {
            case "google" -> properties.google().baseUrl();
            case "mapbox" -> properties.mapbox().baseUrl();
            default -> osm.osrmUrl();
        };
        String version = GuardedRouteDistanceService.sha(cache.version() + "|" + endpoint).substring(0, 12);
        return new GuardedRouteDistanceService(service, redis, json, new GuardedRouteDistanceService.Limits(cache.ttl(), properties.routeQuota().perHour(),
                properties.routeQuota().perDay(), cache.lockWait(), version), clock, metrics);
    }

    private static RestClient client(RouteProperties properties, String userAgent) {
        RouteProperties.Google timeouts = properties.google();
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeouts.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeouts.readTimeout());
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (userAgent != null) builder.defaultHeader("User-Agent", userAgent);
        return builder.build();
    }

    private static RouteCall call(RestClient http, RouteProperties properties) {
        return new RouteCall(http, properties.routeRetry().maxAttempts(), properties.routeRetry().backoff());
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
