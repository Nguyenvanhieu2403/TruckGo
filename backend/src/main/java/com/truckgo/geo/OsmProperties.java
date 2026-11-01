package com.truckgo.geo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.maps.osm.*: free OpenStreetMap-based services. nominatimUrl / photonUrl / osrmUrl default to the public servers, which only
 * allow light use (about 1 request/second, an identifying User-Agent, results cached); point them at your own
 * Nominatim/OSRM for production volume. userAgent identifies the deployment as the policy requires.
 * tileUrl/attribution are handed to the browser for the map (attribution is mandatory for OSM tiles).
 */
@ConfigurationProperties("app.maps.osm")
public record OsmProperties(String nominatimUrl, String photonUrl, String osrmUrl, String userAgent, Duration spacing, Duration maxWait,
                            String tileUrl, String tileAttribution, int tileMaxZoom) { }
