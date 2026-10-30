package com.truckgo.geo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeoConfig {
    /** app.maps.provider: only "none" exists in Sprint 3; vendor adapters plug in here. */
    @Bean
    GeocodingProvider geocodingProvider(@Value("${app.maps.provider:none}") String provider) {
        if (!"none".equalsIgnoreCase(provider.trim())) throw new IllegalStateException("Unsupported app.maps.provider: " + provider);
        return new NoGeocodingProvider();
    }
}
