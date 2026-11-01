package com.truckgo.shipment;

import com.truckgo.geo.GeoPoint;
import com.truckgo.geo.GeocodingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fills blank stop coordinates from the geocoding provider in the background, after the save committed, so saving a
 * draft never waits for (rate-limited, free) map services. Only a precise, full match is used, and the update is
 * conditional: it applies only while the stop still has no coordinates and its address is unchanged, so it can never
 * overwrite coordinates the customer entered or an address they edited meanwhile.
 */
@Component
public class StopCoordinateFiller {
    private static final Logger log = LoggerFactory.getLogger(StopCoordinateFiller.class);

    private final GeocodingProvider geocoder;
    private final JdbcTemplate jdbc;
    private final TaskExecutor executor;

    public StopCoordinateFiller(GeocodingProvider geocoder, JdbcTemplate jdbc, @Qualifier("geocodingExecutor") TaskExecutor executor) {
        this.geocoder = geocoder;
        this.jdbc = jdbc;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStopsSaved(ShipmentStopsSavedEvent event) {
        if (!geocoder.enabled()) return;
        try {
            executor.execute(() -> fill(event.shipmentId()));
        } catch (TaskRejectedException ex) {
            log.warn("Geocoding queue full, stops of shipment {} keep blank coordinates", event.shipmentId());
        }
    }

    /** Returns how many stops received coordinates. */
    int fill(UUID shipmentId) {
        List<Map<String, Object>> stops;
        try {
            stops = jdbc.queryForList("""
                    SELECT st.id, st.full_address FROM shipment_stops st JOIN shipments s ON s.id = st.shipment_id
                     WHERE st.shipment_id = ? AND st.latitude IS NULL AND st.longitude IS NULL AND s.status IN ('DRAFT', 'PUBLISHED')
                     ORDER BY st.sequence_no""", shipmentId);
        } catch (RuntimeException ex) {
            log.warn("Coordinate filling skipped for shipment {}: {}", shipmentId, ex.getClass().getSimpleName());
            return 0;
        }
        int filled = 0;
        for (Map<String, Object> stop : stops) {
            String address = (String) stop.get("full_address");
            Optional<GeoPoint> point;
            try { point = geocoder.geocode(address); } catch (RuntimeException ex) { point = Optional.empty(); }
            if (point.isEmpty()) continue;
            filled += jdbc.update("""
                    UPDATE shipment_stops SET latitude = ?, longitude = ?
                     WHERE id = ? AND latitude IS NULL AND longitude IS NULL AND full_address = ?""",
                    point.get().latitude(), point.get().longitude(), stop.get("id"), address);
        }
        if (filled > 0) log.info("Filled coordinates of {} stop(s) of shipment {}", filled, shipmentId);
        return filled;
    }
}
