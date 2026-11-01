package com.truckgo.geo;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Photon (komoot) — free geocoder on OpenStreetMap data, no key. Results are limited to the service-area bounding box
 * and to country code VN, requests are spaced ({@link ProviderThrottle}, fair use of the public server) and cached.
 *
 * <p>Precision from Photon's type: a house (with a house number or a named building) is EXACT; a street is CENTER;
 * localities and larger areas are APPROXIMATE. Only EXACT/INTERPOLATED ever fill coordinates automatically.
 */
public class PhotonGeocodingProvider implements GeocodingProvider {
    public static final String NAME = "photon";
    private static final Logger log = LoggerFactory.getLogger(PhotonGeocodingProvider.class);

    private final RestClient http;
    private final String baseUrl;
    private final GeoValidator validator;
    private final GeoProperties area;
    private final ProviderThrottle throttle;

    public PhotonGeocodingProvider(RestClient http, String baseUrl, GeoValidator validator, GeoProperties area, ProviderThrottle throttle) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.validator = validator;
        this.area = area;
        this.throttle = throttle;
    }

    @Override public String name() { return NAME; }

    @Override
    public List<GeoCandidate> search(String query, int limit) {
        if (!throttle.acquire()) throw new GeocodingUnavailableException("Dịch vụ bản đồ miễn phí đang bận, vui lòng thử lại sau vài giây");
        String bbox = area.minLongitude().toPlainString() + "," + area.minLatitude().toPlainString() + "," + area.maxLongitude().toPlainString() + ","
                + area.maxLatitude().toPlainString();
        var uri = UriComponentsBuilder.fromUriString(baseUrl).path("/api/").queryParam("q", query).queryParam("limit", Math.min(limit * 2, 10))
                .queryParam("bbox", bbox).encode().build().toUri();
        JsonNode body;
        try {
            body = http.get().uri(uri).retrieve().body(JsonNode.class);
        } catch (RuntimeException ex) {
            log.warn("Photon call failed: {}", ex.getClass().getSimpleName());
            throw new GeocodingUnavailableException("Không kết nối được dịch vụ bản đồ");
        }
        if (body == null || !body.path("features").isArray()) throw new GeocodingUnavailableException("Dịch vụ bản đồ trả về dữ liệu không hợp lệ");
        List<GeoCandidate> results = new ArrayList<>();
        for (JsonNode f : body.path("features")) {
            JsonNode p = f.path("properties");
            JsonNode c = f.path("geometry").path("coordinates");
            if (!"VN".equalsIgnoreCase(p.path("countrycode").asText("")) || c.size() < 2) continue;
            GeoCandidate candidate = new GeoCandidate(label(p), new BigDecimal(c.get(1).asText()), new BigDecimal(c.get(0).asText()),
                    precision(p, query), false, p.hasNonNull("osm_id") ? "osm:" + p.path("osm_type").asText("") + p.path("osm_id").asText() : null);
            validator.validate(candidate).ifPresent(results::add);
            if (results.size() >= limit) break;
        }
        return results;
    }

    /**
     * Photon matches fuzzily and has no partial-match flag: "Số 5 Hoàng Quốc Việt, Hà Nội" can come back as a school
     * on a Hoàng Quốc Việt street in another province. A result is EXACT only when its house number and street both
     * appear in what the user typed (accent-insensitive); other buildings and streets are CENTER (suggestions the user
     * may pick), areas APPROXIMATE. Only EXACT ever fills coordinates on its own.
     */
    static GeoCandidate.Precision precision(JsonNode p, String query) {
        String type = p.path("type").asText("");
        if ("house".equals(type)) {
            String number = p.path("housenumber").asText("").trim(), street = p.path("street").asText("").trim();
            String q = " " + AddressText.normalize(query) + " ";
            boolean numberMatches = !number.isEmpty() && q.contains(" " + AddressText.normalize(number) + " ");
            boolean streetMatches = !street.isEmpty() && q.contains(" " + AddressText.streetName(street) + " ");
            return numberMatches && streetMatches ? GeoCandidate.Precision.EXACT : GeoCandidate.Precision.CENTER;
        }
        return "street".equals(type) ? GeoCandidate.Precision.CENTER : GeoCandidate.Precision.APPROXIMATE;
    }

    /** "96 Phố Trần Thái Tông, Cầu Giấy, Hà Nội" style label from Photon's parts, without repeats. */
    static String label(JsonNode p) {
        Set<String> parts = new LinkedHashSet<>();
        String number = p.path("housenumber").asText(""), street = p.path("street").asText(""), name = p.path("name").asText("");
        if (!name.isBlank()) parts.add(name);
        if (!street.isBlank()) parts.add(number.isBlank() ? street : number + " " + street);
        else if (!number.isBlank()) parts.add(number);
        for (String k : List.of("district", "city", "county", "state")) {
            String v = p.path(k).asText("");
            if (!v.isBlank()) parts.add(v);
        }
        return String.join(", ", parts);
    }
}
