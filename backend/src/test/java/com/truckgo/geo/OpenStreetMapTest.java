package com.truckgo.geo;

import com.truckgo.pricing.route.OsrmRouteDistanceService;
import com.truckgo.pricing.route.RouteDistanceResult;
import com.truckgo.shipment.ShipmentStop;
import com.truckgo.shipment.StopType;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenStreetMapTest {
    @SuppressWarnings("unchecked")
    private static ProviderThrottle throttle(boolean free) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(free);
        return new ProviderThrottle(redis, "test", Duration.ofMillis(1100), Duration.ofMillis(300));
    }

    private static NominatimGeocodingProvider nominatim(RestClient.Builder builder, boolean free) {
        return new NominatimGeocodingProvider(builder.defaultHeader("User-Agent", "TruckGo/test").build(), "https://nominatim.example.test",
                new GeoValidator(GeocodingTest.PROPS), throttle(free));
    }

    @Test void nominatimResultsAreRankedByPrecisionAndRestrictedToVietNam() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<String> url = new AtomicReference<>();
        AtomicReference<String> agent = new AtomicReference<>();
        server.expect(r -> { url.set(r.getURI().toString()); agent.set(r.getHeaders().getFirst("User-Agent")); }).andRespond(withSuccess("""
                [{"place_id":1,"lat":"21.0368","lon":"105.7826","display_name":"Tòa nhà A, Cầu Giấy, Hà Nội","place_rank":30},
                 {"place_id":2,"lat":"21.0371","lon":"105.7880","display_name":"Trần Thái Tông, Cầu Giấy, Hà Nội","place_rank":26},
                 {"place_id":3,"lat":"21.0300","lon":"105.8000","display_name":"Cầu Giấy, Hà Nội","place_rank":18},
                 {"place_id":4,"lat":"13.75","lon":"100.50","display_name":"Bangkok","place_rank":16}]""", MediaType.APPLICATION_JSON));
        List<GeoCandidate> results = nominatim(builder, true).search("Trần Thái Tông, Cầu Giấy", 5);
        assertThat(url.get()).contains("countrycodes=vn").contains("format=jsonv2").contains("/search");
        assertThat(agent.get()).isEqualTo("TruckGo/test");   // the usage policy requires an identifying User-Agent
        assertThat(results).extracting(GeoCandidate::precision)
                .containsExactly(GeoCandidate.Precision.EXACT, GeoCandidate.Precision.CENTER, GeoCandidate.Precision.APPROXIMATE);
        assertThat(results.get(0).isPreciseFullMatch()).isTrue();
        assertThat(results.get(1).isPreciseFullMatch()).isFalse();   // a street centre never fills coordinates by itself
        assertThat(results.get(0).placeId()).isEqualTo("osm:1");
    }

    @Test void nominatimFailuresAndThrottlingAreUnavailableNotEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(r -> { }).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> nominatim(builder, true).search("Hà Nội", 5)).isInstanceOf(GeocodingUnavailableException.class);
        assertThatThrownBy(() -> nominatim(RestClient.builder(), false).search("Hà Nội", 5))
                .isInstanceOf(GeocodingUnavailableException.class).hasMessageContaining("đang bận");
        assertThat(nominatim(RestClient.builder(), false).geocode("Hà Nội")).isEmpty();
    }

    @Test void photonReturnsVietnameseAddressesWithHonestPrecision() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<String> url = new AtomicReference<>();
        // Shape of a real answer from photon.komoot.io for "Trần Thái Tông, Cầu Giấy, Hà Nội".
        server.expect(r -> url.set(r.getURI().toString())).andRespond(withSuccess("""
                {"features":[
                 {"geometry":{"coordinates":[105.7866072,21.0286768]},"properties":{"osm_type":"N","osm_id":11,"type":"house","countrycode":"VN",
                   "name":"Đảng uỷ - UBND phường Cầu Giấy","housenumber":"96","street":"Phố Trần Thái Tông","district":"Cầu Giấy","city":"Hà Nội","state":"Hà Nội"}},
                 {"geometry":{"coordinates":[105.7894307,21.0362147]},"properties":{"osm_type":"W","osm_id":12,"type":"street","countrycode":"VN",
                   "name":"Phố Trần Thái Tông","district":"Cầu Giấy","city":"Hà Nội"}},
                 {"geometry":{"coordinates":[100.5,13.75]},"properties":{"type":"city","countrycode":"TH","name":"Bangkok"}}]}""", MediaType.APPLICATION_JSON));
        List<GeoCandidate> results = new PhotonGeocodingProvider(builder.build(), "https://photon.example.test", new GeoValidator(GeocodingTest.PROPS),
                GeocodingTest.PROPS, throttle(true)).search("96 Trần Thái Tông, Cầu Giấy, Hà Nội", 5);
        assertThat(url.get()).contains("/api/").contains("bbox=102.0,8.0,110.0,23.5");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).formattedAddress()).isEqualTo("Đảng uỷ - UBND phường Cầu Giấy, 96 Phố Trần Thái Tông, Cầu Giấy, Hà Nội");
        assertThat(results.get(0).precision()).isEqualTo(GeoCandidate.Precision.EXACT);
        assertThat(results.get(0).latitude()).isEqualByComparingTo("21.028677");
        assertThat(results.get(1).precision()).isEqualTo(GeoCandidate.Precision.CENTER);
        assertThat(results.get(1).isPreciseFullMatch()).isFalse();
    }

    @Test void photonFuzzyMatchesNeverCountAsExact() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Real answers for "Số 5 Hoàng Quốc Việt, Hà Nội": same street name in Bắc Ninh, a building without a number.
        server.expect(r -> { }).andRespond(withSuccess("""
                {"features":[
                 {"geometry":{"coordinates":[105.9736255,21.1324968]},"properties":{"type":"house","countrycode":"VN","name":"Trường Đại học Luật Hà Nội",
                   "street":"Đường Hoàng Quốc Việt","district":"Đồng Nguyên","city":"Thành phố Bắc Ninh"}},
                 {"geometry":{"coordinates":[105.9680817,21.118209]},"properties":{"type":"house","countrycode":"VN","name":"Chi cục Hải quân",
                   "housenumber":"1","street":"Phố Kim Lân","city":"Thành phố Bắc Ninh"}},
                 {"geometry":{"coordinates":[105.80,21.046]},"properties":{"type":"house","countrycode":"VN","housenumber":"5","street":"Đường Hoàng Quốc Việt","city":"Hà Nội"}}]}""",
                MediaType.APPLICATION_JSON));
        List<GeoCandidate> results = new PhotonGeocodingProvider(builder.build(), "https://photon.example.test", new GeoValidator(GeocodingTest.PROPS),
                GeocodingTest.PROPS, throttle(true)).search("Số 5 Hoàng Quốc Việt, Hà Nội", 5);
        assertThat(results).extracting(GeoCandidate::precision)
                .containsExactly(GeoCandidate.Precision.CENTER, GeoCandidate.Precision.CENTER, GeoCandidate.Precision.EXACT);
        assertThat(results).filteredOn(GeoCandidate::isPreciseFullMatch).extracting(GeoCandidate::latitude)
                .singleElement().satisfies(v -> assertThat(v).isEqualByComparingTo("21.046"));
    }

    private static ShipmentStop stop(StopType type, int seq, String lat, String lng) {
        return new ShipmentStop(type, seq, "A", "+84912345678", "Hà Nội", null, null, "x", "Số " + seq + ", Hà Nội",
                lat == null ? null : new BigDecimal(lat), lng == null ? null : new BigDecimal(lng), null);
    }

    @Test void osrmRoutesThroughEveryStopInOrder() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<String> path = new AtomicReference<>();
        server.expect(r -> path.set(r.getURI().getPath())).andRespond(withSuccess("""
                {"code":"Ok","routes":[{"distance":104523.4,"duration":6120.5}]}""", MediaType.APPLICATION_JSON));
        OsrmRouteDistanceService osrm = new OsrmRouteDistanceService(new com.truckgo.pricing.route.RouteCall(builder.build(), 1, java.time.Duration.ZERO), "https://osrm.example.test/", new BigDecimal("3000"), throttle(true));
        RouteDistanceResult r = osrm.calculateDistance(List.of(stop(StopType.PICKUP, 1, "21.0285", "105.8542"), stop(StopType.DELIVERY, 2, "20.8449", "106.6881")));
        assertThat(path.get()).isEqualTo("/route/v1/driving/105.8542,21.0285;106.6881,20.8449");   // OSRM wants lng,lat
        assertThat(r.available()).isTrue();
        assertThat(r.distanceKm()).isEqualByComparingTo("104.52");
        assertThat(r.durationMinutes()).isEqualTo(102);
    }

    @Test void osrmNeedsCoordinatesAndNeverGuesses() {
        OsrmRouteDistanceService osrm = new OsrmRouteDistanceService(new com.truckgo.pricing.route.RouteCall(RestClient.builder().build(), 1, java.time.Duration.ZERO), "https://osrm.example.test", new BigDecimal("3000"), throttle(true));
        RouteDistanceResult missing = osrm.calculateDistance(List.of(stop(StopType.PICKUP, 1, "21.0285", "105.8542"), stop(StopType.DELIVERY, 2, null, null)));
        assertThat(missing.available()).isFalse();
        assertThat(missing.reasonCode()).isEqualTo("MISSING_COORDINATES");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(r -> { }).andRespond(withSuccess("{\"code\":\"NoRoute\",\"routes\":[]}", MediaType.APPLICATION_JSON));
        RouteDistanceResult noRoute = new OsrmRouteDistanceService(new com.truckgo.pricing.route.RouteCall(builder.build(), 1, java.time.Duration.ZERO), "https://osrm.example.test", new BigDecimal("3000"), throttle(true))
                .calculateDistance(List.of(stop(StopType.PICKUP, 1, "21.0285", "105.8542"), stop(StopType.DELIVERY, 2, "10.7769", "106.7009")));
        assertThat(noRoute.reasonCode()).isEqualTo("ROUTE_NOT_FOUND");
        RouteDistanceResult busy = new OsrmRouteDistanceService(new com.truckgo.pricing.route.RouteCall(RestClient.builder().build(), 1, java.time.Duration.ZERO), "https://osrm.example.test", new BigDecimal("3000"), throttle(false))
                .calculateDistance(List.of(stop(StopType.PICKUP, 1, "21.0285", "105.8542"), stop(StopType.DELIVERY, 2, "20.8449", "106.6881")));
        assertThat(busy.reasonCode()).isEqualTo("PROVIDER_BUSY");
    }

    @Test void throttleFallsBackToThisInstanceWhenRedisIsDown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        ProviderThrottle t = new ProviderThrottle(redis, "x", Duration.ofSeconds(5), Duration.ZERO);
        assertThat(t.acquire()).isTrue();
        assertThat(t.acquire()).isFalse();   // second call within the spacing is refused locally
    }
}
