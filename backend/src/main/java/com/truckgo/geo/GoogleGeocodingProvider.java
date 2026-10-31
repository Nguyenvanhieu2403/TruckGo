package com.truckgo.geo;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Maps Geocoding API restricted to Viet Nam (region=vn, components=country:VN, language=vi). The API key is
 * never logged: HTTP client exception messages contain the URL, so only the exception type is.
 */
public class GoogleGeocodingProvider implements GeocodingProvider {
    public static final String NAME = "google";
    private static final Logger log = LoggerFactory.getLogger(GoogleGeocodingProvider.class);

    private final RestClient http;
    private final String baseUrl;
    private final String apiKey;
    private final GeoValidator validator;

    public GoogleGeocodingProvider(RestClient http, String baseUrl, String apiKey, GeoValidator validator) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.validator = validator;
    }

    @Override public String name() { return NAME; }

    @Override
    public List<GeoCandidate> search(String query, int limit) {
        var uri = UriComponentsBuilder.fromUriString(baseUrl).queryParam("address", query).queryParam("region", "vn")
                .queryParam("components", "country:VN").queryParam("language", "vi").queryParam("key", apiKey).encode().build().toUri();
        JsonNode body;
        try {
            body = http.get().uri(uri).retrieve().body(JsonNode.class);
        } catch (RuntimeException ex) {
            log.warn("Geocoding provider call failed: {}", ex.getClass().getSimpleName());
            throw new GeocodingUnavailableException("Không kết nối được dịch vụ bản đồ");
        }
        String status = body == null ? "EMPTY" : body.path("status").asText("EMPTY");
        if ("ZERO_RESULTS".equals(status)) return List.of();
        if (!"OK".equals(status)) {
            log.warn("Geocoding provider returned status {}", status);
            throw new GeocodingUnavailableException("Dịch vụ bản đồ tạm thời không khả dụng (" + status + ")");
        }
        List<GeoCandidate> results = new ArrayList<>();
        for (JsonNode r : body.path("results")) {
            JsonNode location = r.path("geometry").path("location");
            if (!location.hasNonNull("lat") || !location.hasNonNull("lng")) continue;
            GeoCandidate candidate = new GeoCandidate(r.path("formatted_address").asText(null), new BigDecimal(location.path("lat").asText()),
                    new BigDecimal(location.path("lng").asText()), precision(r.path("geometry").path("location_type").asText("")),
                    r.path("partial_match").asBoolean(false), r.path("place_id").asText(null));
            validator.validate(candidate).ifPresent(results::add);
            if (results.size() >= limit) break;
        }
        return results;
    }

    static GeoCandidate.Precision precision(String locationType) {
        return switch (locationType) {
            case "ROOFTOP" -> GeoCandidate.Precision.EXACT;
            case "RANGE_INTERPOLATED" -> GeoCandidate.Precision.INTERPOLATED;
            case "GEOMETRIC_CENTER" -> GeoCandidate.Precision.CENTER;
            default -> GeoCandidate.Precision.APPROXIMATE;
        };
    }
}
