package com.truckgo.marketplace;

import com.truckgo.common.response.PageResponse;
import com.truckgo.driver.DriverProfile;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminAssignmentResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AssignmentOverview;
import com.truckgo.marketplace.dto.MarketplaceDtos.AssignmentView;
import com.truckgo.marketplace.dto.MarketplaceDtos.ScheduleEntry;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.user.User;
import com.truckgo.vehicle.Vehicle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/** Read side of assignments: an order's slots and history, and the busy periods of drivers and vehicles. */
@Component
class AssignmentViews {
    private final ShipmentAssignmentRepository assignments;
    private final ShipmentAssignmentHistoryRepository history;
    private final MarketplaceViews views;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    AssignmentViews(ShipmentAssignmentRepository assignments, ShipmentAssignmentHistoryRepository history, MarketplaceViews views, JdbcTemplate jdbc,
                    NamedParameterJdbcTemplate named) {
        this.assignments = assignments;
        this.history = history;
        this.views = views;
        this.jdbc = jdbc;
        this.named = named;
    }

    AssignmentOverview overview(Shipment s) {
        List<ShipmentAssignment> rows = assignments.findByShipmentIdOrderByAssignedAtAsc(s.getId());
        Map<UUID, DriverProfile> drivers = views.drivers(rows.stream().map(ShipmentAssignment::getDriverId).toList());
        Map<UUID, User> users = views.driverUsers(drivers.values());
        Map<UUID, Vehicle> vehicles = views.vehicles(rows.stream().map(ShipmentAssignment::getVehicleId).toList());
        List<AssignmentView> slots = rows.stream()
                .sorted(java.util.Comparator.comparing((ShipmentAssignment a) -> !a.isActive()).thenComparing(ShipmentAssignment::getSlotNo).thenComparing(ShipmentAssignment::getAssignedAt))
                .map(a -> {
                    DriverProfile d = drivers.get(a.getDriverId());
                    return new AssignmentView(a.getId(), a.getSlotNo(), a.getStatus().name(), a.isActive() && a.getDriverId().equals(s.getAssignedDriverId()),
                            d == null ? null : views.driver(d, users.get(d.getId()), a.isActive()), MarketplaceViews.vehicle(vehicles.get(a.getVehicleId())),
                            a.getApplicationId(), a.getBusyFrom(), a.getBusyUntil(), a.getAssignedAt(), a.getReleasedAt(), a.getReleaseReason(), a.getFinishedAt());
                }).toList();
        int active = (int) rows.stream().filter(ShipmentAssignment::isActive).count();
        String blocked = changeBlockedReason(s);
        return new AssignmentOverview(s.getId(), s.getShipmentCode(), s.getStatus(), s.getVehicleCount(), active, Math.max(s.getVehicleCount() - active, 0),
                blocked == null, blocked, slots, history(history.findByShipmentIdOrderByCreatedAtAsc(s.getId())));
    }

    /** Why drivers of this order can no longer be removed or replaced, or null. */
    String changeBlockedReason(Shipment s) {
        if (s.getStatus() != ShipmentStatus.PUBLISHED && s.getStatus() != ShipmentStatus.ASSIGNED)
            return switch (s.getStatus()) {
                case DRIVER_ARRIVED, PICKED_UP, IN_TRANSIT -> "Chuyến đã bắt đầu; xử lý theo quy trình sự cố";
                default -> "Đơn ở trạng thái " + s.getStatus();
            };
        Long started = jdbc.queryForObject("SELECT count(*) FROM shipment_stop_progress WHERE shipment_id = ? AND status <> 'PENDING'", Long.class, s.getId());
        return started != null && started > 0 ? "Chuyến đã bắt đầu; xử lý theo quy trình sự cố" : null;
    }

    List<AdminAssignmentResponse> history(List<ShipmentAssignmentHistory> content) {
        Map<UUID, Shipment> shipments = views.shipments(content.stream().map(ShipmentAssignmentHistory::getShipmentId).toList());
        Map<UUID, Vehicle> vehicles = views.vehicles(content.stream().map(ShipmentAssignmentHistory::getVehicleId).filter(Objects::nonNull).toList());
        Map<UUID, DriverProfile> drivers = views.drivers(content.stream().flatMap(h -> Stream.of(h.getNewDriverId(), h.getPreviousDriverId())).filter(Objects::nonNull).toList());
        Map<UUID, User> driverUsers = views.driverUsers(drivers.values());
        Map<UUID, User> actors = views.users(content.stream().map(ShipmentAssignmentHistory::getPerformedBy).filter(Objects::nonNull).toList());
        return content.stream().map(h -> {
            Shipment s = shipments.get(h.getShipmentId());
            User driver = h.getNewDriverId() == null ? null : driverUsers.get(h.getNewDriverId());
            User previous = h.getPreviousDriverId() == null ? null : driverUsers.get(h.getPreviousDriverId());
            Vehicle v = h.getVehicleId() == null ? null : vehicles.get(h.getVehicleId());
            User actor = h.getPerformedBy() == null ? null : actors.get(h.getPerformedBy());
            return new AdminAssignmentResponse(h.getId(), h.getShipmentId(), s == null ? null : s.getShipmentCode(), h.getAction(), h.getNewDriverId(),
                    driver == null ? null : driver.getFullName(), h.getPreviousDriverId(), h.getVehicleId(), v == null ? null : v.getPlateNumber(),
                    h.getApplicationId(), h.getReason(), h.getPerformedBy(), actor == null ? null : actor.getFullName(), h.getCreatedAt(),
                    previous == null ? null : previous.getFullName(), h.getActorType(), h.getSystemActor(), h.getAssignmentId());
        }).toList();
    }

    /** Active busy periods overlapping [from, to), optionally for one driver or vehicle, earliest first. */
    PageResponse<ScheduleEntry> schedules(UUID driverId, UUID vehicleId, Instant from, Instant to, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        StringBuilder where = new StringBuilder(" WHERE a.status = 'ACTIVE' AND a.busy_period && tstzrange(:from, :to, '[)')");
        if (driverId != null) { where.append(" AND a.driver_id = :driver"); p.addValue("driver", driverId); }
        if (vehicleId != null) { where.append(" AND a.vehicle_id = :vehicle"); p.addValue("vehicle", vehicleId); }
        String from_ = """
                 FROM shipment_assignments a JOIN shipments s ON s.id = a.shipment_id
                 JOIN driver_profiles dp ON dp.id = a.driver_id JOIN users u ON u.id = dp.user_id
                 JOIN vehicles v ON v.id = a.vehicle_id
                """;
        long total = named.queryForObject("SELECT count(*)" + from_ + where, p, Long.class);
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), 100);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<ScheduleEntry> rows = named.query("""
                SELECT a.id, s.id AS shipment_id, s.shipment_code, s.status, a.driver_id, u.full_name, a.vehicle_id, v.plate_number,
                       lower(a.busy_period) AS busy_from, upper(a.busy_period) AS busy_until, s.assigned_driver_id = a.driver_id AS lead
                """ + from_ + where + " ORDER BY lower(a.busy_period), a.id LIMIT :limit OFFSET :offset", p,
                (rs, i) -> new ScheduleEntry(rs.getObject("id", UUID.class), rs.getObject("shipment_id", UUID.class), rs.getString("shipment_code"),
                        ShipmentStatus.valueOf(rs.getString("status")), rs.getObject("driver_id", UUID.class), rs.getString("full_name"),
                        rs.getObject("vehicle_id", UUID.class), rs.getString("plate_number"),
                        rs.getTimestamp("busy_from") == null ? null : rs.getTimestamp("busy_from").toInstant(),
                        rs.getTimestamp("busy_until") == null ? null : rs.getTimestamp("busy_until").toInstant(), rs.getBoolean("lead")));
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }
}
