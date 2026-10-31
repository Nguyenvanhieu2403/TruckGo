package com.truckgo.geo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * app.maps.geocoding.*: provider answers outside the service area are discarded (default: mainland Viet Nam); results
 * are cached for cacheTtl; each user may search at most searchesPerMinute times (protects the paid API key).
 */
@ConfigurationProperties("app.maps.geocoding")
public record GeoProperties(String provider, String baseUrl, Duration cacheTtl, int searchesPerMinute, int maxResults,
                            BigDecimal minLatitude, BigDecimal maxLatitude, BigDecimal minLongitude, BigDecimal maxLongitude) { }
