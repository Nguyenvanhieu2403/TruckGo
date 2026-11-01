package com.truckgo.marketplace;

import com.truckgo.shipment.Shipment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * When a driver and vehicle are busy with an order, and which of their other active assignments overlap. This is the
 * friendly pre-check (it names the conflicting order); the exclusion constraints on shipment_assignments are what make
 * two concurrent assignments of the same driver or vehicle impossible.
 */
@Component
public class ScheduleService {
    /** [from, until): pickup until the end of the trip plus the buffer before the next one. */
    public record Period(Instant from, Instant until) { }

    public record Conflict(boolean driver, boolean vehicle, UUID shipmentId, String shipmentCode, Instant from, Instant until) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final MarketplaceProperties properties;

    public ScheduleService(NamedParameterJdbcTemplate jdbc, MarketplaceProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** Null when the order has no pickup time (it cannot be assigned then). */
    public Period period(Shipment shipment) {
        Instant pickup = shipment.getPickupTime();
        if (pickup == null) return null;
        Instant end = shipment.getDeliveryDeadline() != null && shipment.getDeliveryDeadline().isAfter(pickup)
                ? shipment.getDeliveryDeadline() : pickup.plus(properties.defaultTripDuration());
        return new Period(pickup, end.plus(properties.scheduleBuffer()));
    }

    /**
     * Active assignments on other orders that overlap the period and use this driver or this vehicle (either may be
     * null to check only the other). {@code excludeShipmentId} is the order being assigned.
     */
    public List<Conflict> conflicts(UUID driverId, UUID vehicleId, Period period, UUID excludeShipmentId) {
        if (period == null || (driverId == null && vehicleId == null)) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", Timestamp.from(period.from())).addValue("until", Timestamp.from(period.until()))
                .addValue("exclude", excludeShipmentId);
        String driverMatch = driverId == null ? "FALSE" : "a.driver_id = :driver";
        String vehicleMatch = vehicleId == null ? "FALSE" : "a.vehicle_id = :vehicle";
        if (driverId != null) p.addValue("driver", driverId);
        if (vehicleId != null) p.addValue("vehicle", vehicleId);
        return jdbc.query("SELECT " + driverMatch + " AS same_driver, " + vehicleMatch + """
                 AS same_vehicle, s.id, s.shipment_code, lower(a.busy_period) AS busy_from, upper(a.busy_period) AS busy_until
                  FROM shipment_assignments a JOIN shipments s ON s.id = a.shipment_id
                 WHERE a.status = 'ACTIVE' AND a.busy_period && tstzrange(:from, :until, '[)') AND a.shipment_id <> :exclude
                """ + " AND (" + driverMatch + " OR " + vehicleMatch + ") ORDER BY lower(a.busy_period)", p,
                (rs, i) -> new Conflict(rs.getBoolean("same_driver"), rs.getBoolean("same_vehicle"), rs.getObject("id", UUID.class),
                        rs.getString("shipment_code"), rs.getTimestamp("busy_from").toInstant(), rs.getTimestamp("busy_until").toInstant()));
    }

    /** Human-readable reasons, driver first, one per conflicting order. */
    public static List<String> reasons(List<Conflict> conflicts) {
        Set<String> out = new LinkedHashSet<>();
        for (Conflict c : conflicts) {
            if (c.driver()) out.add("Tài xế đã có chuyến " + c.shipmentCode() + " trùng thời gian");
            if (c.vehicle()) out.add("Xe đã có lịch chuyến " + c.shipmentCode() + " trùng thời gian");
        }
        return new ArrayList<>(out);
    }

    /** Vehicle-only reasons (shown per vehicle to a driver choosing which one to apply with). */
    public static List<String> vehicleReasons(List<Conflict> conflicts) {
        return reasons(conflicts.stream().filter(Conflict::vehicle).map(c -> new Conflict(false, true, c.shipmentId(), c.shipmentCode(), c.from(), c.until())).toList());
    }
}
