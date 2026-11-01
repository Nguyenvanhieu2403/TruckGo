package com.truckgo.pricing;

import com.truckgo.pricing.route.GoogleRouteDistanceService;
import com.truckgo.pricing.route.ManualRouteDistanceService;
import com.truckgo.pricing.route.RouteDistanceResult;
import com.truckgo.pricing.route.RouteCall;
import com.truckgo.pricing.route.RouteProperties;
import com.truckgo.shipment.ShipmentStop;
import com.truckgo.shipment.StopType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleRouteDistanceServiceTest {
    private static final String URL = "https://maps.example.test/directions/json";
    private final RouteProperties properties = new RouteProperties("google",
            new RouteProperties.Google("secret-key-123", URL, Duration.ofSeconds(1), Duration.ofSeconds(1)), null, new BigDecimal("3000"),
            new RouteProperties.RouteRetry(2, Duration.ZERO), null, null);
    private MockRestServiceServer server;
    private GoogleRouteDistanceService service;

    @BeforeEach void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new GoogleRouteDistanceService(new RouteCall(builder.build(), 2, Duration.ZERO), properties);
    }

    static ShipmentStop stop(StopType type, int seq, String lat, String lng, String address) {
        return new ShipmentStop(type, seq, "A", "+84912345678", "Hà Nội", null, null, "x", address,
                lat == null ? null : new BigDecimal(lat), lng == null ? null : new BigDecimal(lng), null);
    }

    @Test void sumsAllLegsOfAMultiStopRouteInOrder() {
        AtomicReference<URI> called = new AtomicReference<>();
        server.expect(r -> called.set(r.getURI())).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"OK","routes":[{"overview_polyline":{"points":"abc"},"legs":[
                          {"distance":{"value":12345},"duration":{"value":1200}},
                          {"distance":{"value":7655},"duration":{"value":610}}]}]}""", MediaType.APPLICATION_JSON));
        RouteDistanceResult result = service.calculateDistance(List.of(stop(StopType.PICKUP, 1, "21.0285", "105.8542", null),
                stop(StopType.DELIVERY, 2, null, null, "Số 20, Dịch Vọng, Cầu Giấy, Hà Nội"), stop(StopType.DELIVERY, 3, "21.0362", "105.7906", null)));
        assertThat(result.available()).isTrue();
        assertThat(result.distanceKm()).isEqualByComparingTo("20.00");
        assertThat(result.durationMinutes()).isEqualTo(30);
        assertThat(result.encodedPolyline()).isEqualTo("abc");
        String query = URLDecoder.decode(called.get().getRawQuery(), StandardCharsets.UTF_8);
        assertThat(query).contains("origin=21.0285,105.8542").contains("destination=21.0362,105.7906")
                .contains("waypoints=Số 20, Dịch Vọng, Cầu Giấy, Hà Nội").contains("mode=driving").contains("key=secret-key-123");
        server.verify();
    }

    @Test void noRouteMeansUnavailableNotAGuess() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL))).andRespond(withSuccess("{\"status\":\"ZERO_RESULTS\",\"routes\":[]}", MediaType.APPLICATION_JSON));
        RouteDistanceResult result = service.calculateDistance(List.of(stop(StopType.PICKUP, 1, "1", "1", null), stop(StopType.DELIVERY, 2, "2", "2", null)));
        assertThat(result.available()).isFalse();
        assertThat(result.distanceKm()).isNull();
        assertThat(result.reasonCode()).isEqualTo("ROUTE_NOT_FOUND");
    }

    @Test void providerErrorsAndTimeoutsAreRetriedOnceThenUnavailableAndNeverLeakTheKey() {
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        RouteDistanceResult error = service.calculateDistance(List.of(stop(StopType.PICKUP, 1, "1", "1", null), stop(StopType.DELIVERY, 2, "2", "2", null)));
        assertThat(error.available()).isFalse();
        assertThat(error.reasonCode()).isEqualTo("PROVIDER_ERROR");
        assertThat(error.message()).doesNotContain("secret-key-123");

        server.reset();
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andRespond(r -> { throw new ResourceAccessException("Read timed out " + r.getURI()); });
        RouteDistanceResult timeout = service.calculateDistance(List.of(stop(StopType.PICKUP, 1, "1", "1", null), stop(StopType.DELIVERY, 2, "2", "2", null)));
        assertThat(timeout.available()).isFalse();
        assertThat(timeout.message()).doesNotContain("secret-key-123");
        server.verify();
    }

    @Test void inputErrorsAreNotRetriedAndQuotaIsReportedAsSuch() {
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThat(service.calculateDistance(List.of(stop(StopType.PICKUP, 1, "1", "1", null), stop(StopType.DELIVERY, 2, "2", "2", null))).available()).isFalse();
        server.verify();

        server.reset();
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(service.calculateDistance(List.of(stop(StopType.PICKUP, 1, "1", "1", null), stop(StopType.DELIVERY, 2, "2", "2", null))).reasonCode())
                .isEqualTo("PROVIDER_QUOTA");
        server.verify();

        server.reset();
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andRespond(withSuccess("{\"status\":\"OVER_QUERY_LIMIT\"}", MediaType.APPLICATION_JSON));
        assertThat(service.calculateDistance(List.of(stop(StopType.PICKUP, 1, "1", "1", null), stop(StopType.DELIVERY, 2, "2", "2", null))).reasonCode())
                .isEqualTo("PROVIDER_QUOTA");
        server.verify();
    }

    @Test void deniedKeyAndAbsurdDistancesAreRejected() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL))).andRespond(withSuccess("{\"status\":\"REQUEST_DENIED\"}", MediaType.APPLICATION_JSON));
        assertThat(service.calculateDistance(List.of(stop(StopType.PICKUP, 1, "1", "1", null), stop(StopType.DELIVERY, 2, "2", "2", null))).reasonCode()).isEqualTo("PROVIDER_ERROR");
        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL))).andRespond(withSuccess(
                "{\"status\":\"OK\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":9000000},\"duration\":{\"value\":1}}]}]}", MediaType.APPLICATION_JSON));
        assertThat(service.calculateDistance(List.of(stop(StopType.PICKUP, 1, "1", "1", null), stop(StopType.DELIVERY, 2, "2", "2", null))).reasonCode()).isEqualTo("UNREASONABLE_DISTANCE");
    }

    @Test void manualProviderNeverInventsADistance() {
        RouteDistanceResult result = new ManualRouteDistanceService().calculateDistance(List.of());
        assertThat(result.available()).isFalse();
        assertThat(result.distanceKm()).isNull();
        assertThat(result.reasonCode()).isEqualTo("NO_ROUTE_PROVIDER");
    }
}
