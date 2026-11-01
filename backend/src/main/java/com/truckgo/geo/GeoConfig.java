package com.truckgo.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.pricing.route.RouteProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Locale;

@Configuration
public class GeoConfig {
    private static final Logger log = LoggerFactory.getLogger(GeoConfig.class);

    /**
     * MAPS_GEOCODING_PROVIDER: none (default; manual addresses), photon or osm/nominatim (free, OpenStreetMap data) or
     * google (needs GOOGLE_MAPS_API_KEY). Mapbox
     * or a self-hosted geocoder implements {@link GeocodingProvider} and is added to this switch.
     */
    @Bean
    GeocodingProvider geocodingProvider(GeoProperties geo, RouteProperties maps, OsmProperties osm, StringRedisTemplate redis, ObjectMapper json) {
        String provider = geo.provider() == null ? "none" : geo.provider().trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "none" -> {
                log.info("No geocoding provider configured: addresses are entered manually");
                yield new NoGeocodingProvider();
            }
            case "google" -> {
                RouteProperties.Google google = maps.google();
                if (google == null || google.apiKey() == null || google.apiKey().isBlank())
                    throw new IllegalStateException("MAPS_GEOCODING_PROVIDER=google requires GOOGLE_MAPS_API_KEY");
                log.info("Geocoding provider: google");
                HttpClient client = HttpClient.newBuilder().connectTimeout(google.connectTimeout()).build();
                JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
                factory.setReadTimeout(google.readTimeout());
                yield new CachingGeocodingProvider(new GoogleGeocodingProvider(RestClient.builder().requestFactory(factory).build(), geo.baseUrl(),
                        google.apiKey(), new GeoValidator(geo)), redis, json, geo);
            }
            case "photon" -> {
                log.info("Geocoding provider: Photon (OpenStreetMap data) at {}", osm.photonUrl());
                yield new CachingGeocodingProvider(new PhotonGeocodingProvider(osmClient(osm, maps), osm.photonUrl(), new GeoValidator(geo), geo,
                        new ProviderThrottle(redis, "photon", osm.spacing(), osm.maxWait())), redis, json, geo);
            }
            case "osm", "nominatim" -> {
                log.info("Geocoding provider: OpenStreetMap Nominatim at {}", osm.nominatimUrl());
                yield new CachingGeocodingProvider(new NominatimGeocodingProvider(osmClient(osm, maps), osm.nominatimUrl(), new GeoValidator(geo),
                        new ProviderThrottle(redis, "nominatim", osm.spacing(), osm.maxWait())), redis, json, geo);
            }
            default -> throw new IllegalStateException("Unsupported MAPS_GEOCODING_PROVIDER: " + provider);
        };
    }

    /**
     * One worker for background geocoding (filling stop coordinates after a save): the free providers allow about one
     * request per second anyway. The bounded queue drops work rather than piling up when a provider is slow.
     */
    @Bean
    org.springframework.core.task.TaskExecutor geocodingExecutor() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("geocoding-");
        executor.initialize();
        return executor;
    }

    /** HTTP client for OpenStreetMap services: identifying User-Agent (required by their usage policy) and timeouts. */
    public static RestClient osmClient(OsmProperties osm, RouteProperties maps) {
        RouteProperties.Google timeouts = maps.google();
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeouts.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(timeouts.readTimeout());
        return RestClient.builder().requestFactory(factory).defaultHeader("User-Agent", osm.userAgent()).build();
    }
}
