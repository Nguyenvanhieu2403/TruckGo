package com.truckgo.pricing.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Locale;

@Configuration
public class RouteDistanceConfig {
    private static final Logger log = LoggerFactory.getLogger(RouteDistanceConfig.class);

    @Bean
    RouteDistanceService routeDistanceService(RouteProperties properties, com.truckgo.geo.OsmProperties osm,
                                              org.springframework.data.redis.core.StringRedisTemplate redis) {
        String provider = properties.routeProvider() == null ? "none" : properties.routeProvider().trim().toLowerCase(Locale.ROOT);
        switch (provider) {
            case "none", "manual" -> {
                log.info("No route provider configured: quotes wait for an admin-verified distance");
                return new ManualRouteDistanceService();
            }
            case "google" -> {
                RouteProperties.Google google = properties.google();
                if (google == null || google.apiKey() == null || google.apiKey().isBlank())
                    throw new IllegalStateException("MAPS_ROUTE_PROVIDER=google requires GOOGLE_MAPS_API_KEY");
                HttpClient client = HttpClient.newBuilder().connectTimeout(google.connectTimeout()).build();
                JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
                factory.setReadTimeout(google.readTimeout());
                return new GoogleRouteDistanceService(RestClient.builder().requestFactory(factory).build(), properties);
            }
            case "osrm" -> {
                log.info("Route provider: OSRM at {} (free OpenStreetMap routing; stops need coordinates)", osm.osrmUrl());
                return new OsrmRouteDistanceService(com.truckgo.geo.GeoConfig.osmClient(osm, properties), osm.osrmUrl(), properties.maxReasonableKm(),
                        new com.truckgo.geo.ProviderThrottle(redis, "osrm", osm.spacing(), osm.maxWait()));
            }
            default -> throw new IllegalStateException("Unsupported app.maps.route-provider: " + provider);
        }
    }
}
