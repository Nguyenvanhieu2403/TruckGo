package com.truckgo.geo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeocodingTest {
    private static final String URL = "https://maps.example.test/geocode/json";
    static final GeoProperties PROPS = new GeoProperties("google", URL, Duration.ofHours(24), 30, 5,
            new BigDecimal("8.0"), new BigDecimal("23.5"), new BigDecimal("102.0"), new BigDecimal("110.0"));
    private MockRestServiceServer server;
    private GoogleGeocodingProvider google;

    @BeforeEach void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        google = new GoogleGeocodingProvider(builder.build(), URL, "secret-key-123", new GeoValidator(PROPS));
    }

    private static String result(String address, String lat, String lng, String type, boolean partial) {
        return """
               {"formatted_address":"%s","place_id":"p1","partial_match":%s,"geometry":{"location":{"lat":%s,"lng":%s},"location_type":"%s"}}"""
                .formatted(address, partial, lat, lng, type);
    }

    @Test void returnsValidatedCandidatesRestrictedToVietNam() {
        AtomicReference<String> url = new AtomicReference<>();
        server.expect(r -> url.set(r.getURI().toString())).andRespond(withSuccess("{\"status\":\"OK\",\"results\":[" + String.join(",",
                result("1 Trần Thái Tông, Cầu Giấy, Hà Nội", "21.0285123", "105.7895", "ROOFTOP", false),
                result("Paris, France", "48.8566", "2.3522", "APPROXIMATE", false),
                result("Hà Nội", "21.0278", "105.8342", "APPROXIMATE", false)) + "]}", MediaType.APPLICATION_JSON));
        List<GeoCandidate> results = google.search("1 Trần Thái Tông, Cầu Giấy", 5);
        assertThat(url.get()).contains("components=country:VN").contains("region=vn").contains("key=secret-key-123");
        assertThat(results).hasSize(2);   // the point outside Viet Nam is discarded
        assertThat(results.getFirst().latitude()).isEqualByComparingTo("21.028512");
        assertThat(results.getFirst().precision()).isEqualTo(GeoCandidate.Precision.EXACT);
        assertThat(results.getFirst().isPreciseFullMatch()).isTrue();
        assertThat(results.get(1).isPreciseFullMatch()).isFalse();
    }

    @Test void noResultsIsEmptyButProviderErrorsAreReportedAsUnavailable() {
        server.expect(r -> { }).andRespond(withSuccess("{\"status\":\"ZERO_RESULTS\",\"results\":[]}", MediaType.APPLICATION_JSON));
        assertThat(google.search("không tồn tại", 5)).isEmpty();
        server.reset();
        server.expect(r -> { }).andRespond(withSuccess("{\"status\":\"REQUEST_DENIED\",\"error_message\":\"bad key\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> google.search("Hà Nội", 5)).isInstanceOf(GeocodingUnavailableException.class).hasMessageNotContaining("secret-key-123");
        server.reset();
        server.expect(r -> { }).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> google.search("Hà Nội", 5)).isInstanceOf(GeocodingUnavailableException.class);
    }

    @Test void onlyAPreciseFullMatchIsUsedToFillCoordinates() {
        server.expect(r -> { }).andRespond(withSuccess("{\"status\":\"OK\",\"results\":[" + result("Cầu Giấy, Hà Nội", "21.03", "105.79", "APPROXIMATE", false) + "]}",
                MediaType.APPLICATION_JSON));
        assertThat(google.geocode("Cầu Giấy")).isEmpty();
        server.reset();
        server.expect(r -> { }).andRespond(withSuccess("{\"status\":\"OK\",\"results\":[" + result("Số 1 Ngõ 2, Hà Nội", "21.03", "105.79", "ROOFTOP", true) + "]}",
                MediaType.APPLICATION_JSON));
        assertThat(google.geocode("Số 1 Ngõ 2")).isEmpty();   // partial match
        server.reset();
        server.expect(r -> { }).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(google.geocode("Số 1")).isEmpty();          // provider failure never becomes a coordinate
        server.reset();
        server.expect(r -> { }).andRespond(withSuccess("{\"status\":\"OK\",\"results\":[" + result("Số 1 Trần Thái Tông, Hà Nội", "21.0285", "105.7895", "RANGE_INTERPOLATED", false) + "]}",
                MediaType.APPLICATION_JSON));
        assertThat(google.geocode("Số 1 Trần Thái Tông")).map(GeoPoint::latitude).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("21.0285"));
    }

    @Test void withoutAProviderNothingIsGuessed() {
        NoGeocodingProvider none = new NoGeocodingProvider();
        assertThat(none.enabled()).isFalse();
        assertThat(none.geocode("Số 1 Trần Thái Tông, Hà Nội")).isEmpty();
        assertThatThrownBy(() -> none.search("Hà Nội", 5)).isInstanceOf(GeocodingUnavailableException.class);
    }

    @Test void validatorRejectsImpossibleOrOutOfAreaPoints() {
        GeoValidator v = new GeoValidator(PROPS);
        assertThat(v.validate(new GeoCandidate("x", BigDecimal.ZERO, BigDecimal.ZERO, GeoCandidate.Precision.EXACT, false, null))).isEmpty();
        assertThat(v.validate(new GeoCandidate("x", new BigDecimal("95"), new BigDecimal("105"), GeoCandidate.Precision.EXACT, false, null))).isEmpty();
        assertThat(v.validate(new GeoCandidate(" ", new BigDecimal("21"), new BigDecimal("105"), GeoCandidate.Precision.EXACT, false, null))).isEmpty();
        assertThat(v.validate(new GeoCandidate("Bangkok", new BigDecimal("13.75"), new BigDecimal("100.5"), GeoCandidate.Precision.EXACT, false, null))).isEmpty();
        assertThat(v.validate(new GeoCandidate("Hải Phòng", new BigDecimal("20.8449"), new BigDecimal("106.6881"), GeoCandidate.Precision.EXACT, false, null))).isPresent();
    }

    @Test void cacheKeysDoNotRevealTheAddress() {
        assertThat(CachingGeocodingProvider.hash("Số 1  Trần Thái Tông")).isEqualTo(CachingGeocodingProvider.hash("số 1 trần thái tông"))
                .doesNotContain("Trần").hasSize(32);
    }
}
