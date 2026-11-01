package com.truckgo.shipment;

import com.truckgo.geo.GeoCandidate;
import com.truckgo.geo.GeocodingProvider;
import com.truckgo.geo.GeocodingUnavailableException;
import com.truckgo.geo.NoGeocodingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Background coordinate filling: precise matches only, conditional update, never while saving, never a guess. */
class ShipmentGeocodingTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final List<String> queried = new ArrayList<>();
    private final UUID shipment = UUID.randomUUID();
    private final UUID stopId = UUID.randomUUID();

    private GeocodingProvider provider(Function<String, List<GeoCandidate>> answer) {
        return new GeocodingProvider() {
            @Override public String name() { return "stub"; }
            @Override public List<GeoCandidate> search(String query, int limit) { queried.add(query); return answer.apply(query); }
        };
    }

    private StopCoordinateFiller filler(GeocodingProvider geocoder) {
        when(jdbc.queryForList(anyString(), eq(shipment))).thenReturn(List.of(Map.of("id", stopId, "full_address", "Số 20, Dịch Vọng, Cầu Giấy, Hà Nội")));
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        return new StopCoordinateFiller(geocoder, jdbc, new SyncTaskExecutor());
    }

    @Test void savingADraftNeverCallsTheProvider() {
        Shipment s = new Shipment("TG261101-AAAAAA", UUID.randomUUID());
        new ShipmentDraftAssembler(ShipmentTestData.catalogs(), java.time.Clock.systemUTC())
                .apply(s, ShipmentTestData.draft(List.of(ShipmentTestData.pickup(), ShipmentTestData.delivery()), List.of(ShipmentTestData.boxes()), List.of()));
        assertThat(s.getStops().get(1).getLatitude()).isNull();   // filled later, in the background
    }

    @Test void aPreciseMatchIsWrittenOnlyWhileTheStopIsStillBlankAndUnchanged() {
        StopCoordinateFiller f = filler(provider(q -> List.of(new GeoCandidate(q, new BigDecimal("21.0285"), new BigDecimal("105.8542"), GeoCandidate.Precision.EXACT, false, "p"))));
        f.onStopsSaved(new ShipmentStopsSavedEvent(shipment));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("latitude IS NULL AND longitude IS NULL AND full_address = ?"),
                eq(new BigDecimal("21.0285")), eq(new BigDecimal("105.8542")), eq(stopId), eq("Số 20, Dịch Vọng, Cầu Giấy, Hà Nội"));
        assertThat(queried).containsExactly("Số 20, Dịch Vọng, Cầu Giấy, Hà Nội");
    }

    @Test void coarseMatchesAndProviderFailuresWriteNothing() {
        filler(provider(q -> List.of(new GeoCandidate("Cầu Giấy", new BigDecimal("21.03"), new BigDecimal("105.79"), GeoCandidate.Precision.CENTER, false, "p"))))
                .onStopsSaved(new ShipmentStopsSavedEvent(shipment));
        filler(provider(q -> { throw new GeocodingUnavailableException("down"); })).onStopsSaved(new ShipmentStopsSavedEvent(shipment));
        filler(provider(q -> { throw new IllegalStateException("bug"); })).onStopsSaved(new ShipmentStopsSavedEvent(shipment));
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test void withoutAProviderNothingRuns() {
        filler(new NoGeocodingProvider()).onStopsSaved(new ShipmentStopsSavedEvent(shipment));
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }
}
