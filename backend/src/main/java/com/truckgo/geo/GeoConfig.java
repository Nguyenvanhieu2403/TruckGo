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
     * MAPS_GEOCODING_PROVIDER: none (default; manual addresses) or google (needs GOOGLE_MAPS_API_KEY). Mapbox/OSM
     * adapters implement {@link GeocodingProvider} and are added to this switch.
     */
    @Bean
    GeocodingProvider geocodingProvider(GeoProperties geo, RouteProperties maps, StringRedisTemplate redis, ObjectMapper json) {
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
            default -> throw new IllegalStateException("Unsupported MAPS_GEOCODING_PROVIDER: " + provider);
        };
    }
}
