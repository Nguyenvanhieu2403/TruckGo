package com.truckgo.shipment;

import com.truckgo.geo.GeoCandidate;
import com.truckgo.geo.GeocodingProvider;
import com.truckgo.geo.GeocodingUnavailableException;
import com.truckgo.shipment.dto.ShipmentRequests.StopRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** How the draft assembler uses the geocoder: fill blanks from precise matches only, never overwrite, never guess. */
class ShipmentGeocodingTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    private final List<String> queried = new ArrayList<>();

    private GeocodingProvider provider(Function<String, List<GeoCandidate>> answer) {
        return new GeocodingProvider() {
            @Override public String name() { return "stub"; }
            @Override public List<GeoCandidate> search(String query, int limit) { queried.add(query); return answer.apply(query); }
        };
    }

    private Shipment apply(GeocodingProvider geocoder, List<StopRequest> stops) {
        Shipment shipment = new Shipment("TG261101-AAAAAA", UUID.randomUUID());
        new ShipmentDraftAssembler(ShipmentTestData.catalogs(), geocoder, clock)
                .apply(shipment, ShipmentTestData.draft(stops, List.of(ShipmentTestData.boxes()), List.of()));
        return shipment;
    }

    private static StopRequest withCoordinates(StopRequest s, String lat, String lng) {
        return new StopRequest(s.stopType(), s.sequenceNo(), s.contactName(), s.contactPhone(), s.province(), s.district(), s.ward(), s.addressDetail(),
                new BigDecimal(lat), new BigDecimal(lng), s.note());
    }

    @Test void blankCoordinatesAreFilledFromAPreciseMatch() {
        Shipment s = apply(provider(q -> List.of(new GeoCandidate(q, new BigDecimal("21.028500"), new BigDecimal("105.854200"), GeoCandidate.Precision.EXACT, false, "p"))),
                List.of(ShipmentTestData.pickup(), ShipmentTestData.delivery()));
        assertThat(s.getStops().get(1).getLatitude()).isEqualByComparingTo("21.0285");   // delivery had none
        assertThat(queried).hasSize(1);
        assertThat(queried.getFirst()).contains("Số 20, đường Y").contains("Hà Nội");
    }

    @Test void coordinatesTheCustomerEnteredAreNeverReplaced() {
        Shipment s = apply(provider(q -> List.of(new GeoCandidate(q, new BigDecimal("10.0"), new BigDecimal("106.0"), GeoCandidate.Precision.EXACT, false, "p"))),
                List.of(withCoordinates(ShipmentTestData.pickup(), "21.111111", "105.222222"), ShipmentTestData.delivery()));
        assertThat(s.getStops().getFirst().getLatitude()).isEqualByComparingTo("21.111111");
        assertThat(s.getStops().get(1).getLatitude()).isEqualByComparingTo("10.0");
        assertThat(queried).hasSize(1);   // only the delivery stop without coordinates was looked up
    }

    @Test void approximateMatchesAndProviderFailuresLeaveCoordinatesBlank() {
        Shipment approx = apply(provider(q -> List.of(new GeoCandidate("Hà Nội", new BigDecimal("21.0"), new BigDecimal("105.8"), GeoCandidate.Precision.APPROXIMATE, false, "p"))),
                List.of(ShipmentTestData.pickup(), ShipmentTestData.delivery()));
        assertThat(approx.getStops().get(1).getLatitude()).isNull();
        Shipment failed = apply(provider(q -> { throw new GeocodingUnavailableException("down"); }), List.of(ShipmentTestData.pickup(), ShipmentTestData.delivery()));
        assertThat(failed.getStops().get(1).getLongitude()).isNull();
        Shipment broken = apply(provider(q -> { throw new IllegalStateException("bug in adapter"); }), List.of(ShipmentTestData.pickup(), ShipmentTestData.delivery()));
        assertThat(broken.getStops().get(1).getLatitude()).isNull();
    }
}
