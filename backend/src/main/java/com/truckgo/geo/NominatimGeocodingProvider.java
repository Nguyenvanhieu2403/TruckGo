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
 * OpenStreetMap Nominatim (free, no key), restricted to Viet Nam. Respects the usage policy: identifying User-Agent,
 * at most one request per second for the whole application ({@link ProviderThrottle}) and cached answers (wrapped by
 * {@link CachingGeocodingProvider}). Searches are only sent when the user presses "Tìm", never per keystroke.
 *
 * <p>Precision comes from Nominatim's place_rank: a building or house number is EXACT, anything coarser (a street
 * centre, a ward) is never used to fill coordinates automatically.
 */
public class NominatimGeocodingProvider implements GeocodingProvider {
    public static final String NAME = "osm";
    private static final Logger log = LoggerFactory.getLogger(NominatimGeocodingProvider.class);

    private final RestClient http;
    private final String baseUrl;
    private final GeoValidator validator;
    private final ProviderThrottle throttle;

    public NominatimGeocodingProvider(RestClient http, String baseUrl, GeoValidator validator, ProviderThrottle throttle) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.validator = validator;
        this.throttle = throttle;
    }

    @Override public String name() { return NAME; }

    @Override
    public List<GeoCandidate> search(String query, int limit) {
        if (!throttle.acquire()) throw new GeocodingUnavailableException("Dịch vụ bản đồ miễn phí đang bận, vui lòng thử lại sau vài giây");
        var uri = UriComponentsBuilder.fromUriString(baseUrl).path("/search").queryParam("q", query).queryParam("format", "jsonv2")
                .queryParam("countrycodes", "vn").queryParam("accept-language", "vi").queryParam("limit", Math.min(limit, 10))
                .encode().build().toUri();
        JsonNode body;
        try {
            body = http.get().uri(uri).retrieve().body(JsonNode.class);
        } catch (RuntimeException ex) {
            log.warn("Nominatim call failed: {}", ex.getClass().getSimpleName());
            throw new GeocodingUnavailableException("Không kết nối được dịch vụ bản đồ");
        }
        if (body == null || !body.isArray()) throw new GeocodingUnavailableException("Dịch vụ bản đồ trả về dữ liệu không hợp lệ");
        List<GeoCandidate> results = new ArrayList<>();
        for (JsonNode r : body) {
            if (!r.hasNonNull("lat") || !r.hasNonNull("lon")) continue;
            GeoCandidate c;
            try {
                c = new GeoCandidate(r.path("display_name").asText(null), new BigDecimal(r.path("lat").asText()), new BigDecimal(r.path("lon").asText()),
                        precision(r.path("place_rank").asInt(0)), false, r.hasNonNull("place_id") ? "osm:" + r.path("place_id").asText() : null);
            } catch (NumberFormatException ex) {
                continue;
            }
            validator.validate(c).ifPresent(results::add);
            if (results.size() >= limit) break;
        }
        return results;
    }

    /** https://nominatim.org/release-docs/latest/customize/Ranking/ : 30 = building/house, 26–27 = street, lower = areas. */
    static GeoCandidate.Precision precision(int placeRank) {
        if (placeRank >= 30) return GeoCandidate.Precision.EXACT;
        if (placeRank >= 28) return GeoCandidate.Precision.INTERPOLATED;
        if (placeRank >= 26) return GeoCandidate.Precision.CENTER;
        return GeoCandidate.Precision.APPROXIMATE;
    }
}
