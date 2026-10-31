package com.truckgo.marketplace;

import com.truckgo.catalog.CatalogService;
import com.truckgo.catalog.dto.CatalogDtos.VehicleTypeDto;
import com.truckgo.common.response.PageResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminMarketplaceShipment;
import com.truckgo.marketplace.dto.MarketplaceDtos.Area;
import com.truckgo.marketplace.dto.MarketplaceDtos.MarketplaceShipmentSummary;
import com.truckgo.shipment.ShipmentStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read side of the marketplace in plain SQL: the listing filters on the first pickup and last delivery stop and on
 * the accepted quote, which is awkward and slow through JPA specifications. Every value is a bind parameter.
 */
@Component
public class MarketplaceQuery {
    /** Open order = published, no driver, one vehicle, an accepted quote and still before the application deadline. */
    private static final String FROM = """
             FROM shipments s
             JOIN shipment_quotes q ON q.shipment_id = s.id AND q.status = 'ACCEPTED'
             JOIN LATERAL (SELECT province, district, ward FROM shipment_stops
                            WHERE shipment_id = s.id AND stop_type = 'PICKUP' ORDER BY sequence_no LIMIT 1) p ON TRUE
             JOIN LATERAL (SELECT province, district, ward FROM shipment_stops
                            WHERE shipment_id = s.id AND stop_type = 'DELIVERY' ORDER BY sequence_no DESC LIMIT 1) d ON TRUE
            WHERE s.status = 'PUBLISHED' AND s.assigned_driver_id IS NULL AND s.deleted_at IS NULL
              AND s.vehicle_count = 1 AND s.pickup_time > :deadline
            """;

    private static final String COLUMNS = """
            SELECT s.id, s.shipment_code, s.pickup_time, s.delivery_deadline, s.vehicle_type_code, s.body_type_code, s.total_quantity,
                   s.total_weight_kg, s.total_volume_m3, s.estimated_price, s.published_at, q.distance_km,
                   p.province AS p_province, p.district AS p_district, p.ward AS p_ward,
                   d.province AS d_province, d.district AS d_district, d.ward AS d_ward,
                   (SELECT count(*) FROM shipment_stops x WHERE x.shipment_id = s.id AND x.stop_type = 'DELIVERY') AS delivery_count
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogService catalogs;

    public MarketplaceQuery(NamedParameterJdbcTemplate jdbc, CatalogService catalogs) {
        this.jdbc = jdbc;
        this.catalogs = catalogs;
    }

    public PageResponse<MarketplaceShipmentSummary> openShipments(MarketplaceFilter filter, Instant now, Duration cutoff) {
        MapSqlParameterSource params = new MapSqlParameterSource("deadline", Timestamp.from(now.plus(cutoff)));
        StringBuilder where = new StringBuilder();
        if (filter.keyword() != null) {
            where.append("""
                     AND (s.shipment_code ILIKE :kw ESCAPE '\\'
                          OR unaccent(concat_ws(' ', p.province, p.district, p.ward, d.province, d.district, d.ward)) ILIKE unaccent(:kw) ESCAPE '\\'
                          OR EXISTS (SELECT 1 FROM shipment_items i WHERE i.shipment_id = s.id AND unaccent(i.item_name) ILIKE unaccent(:kw) ESCAPE '\\'))
                    """);
            params.addValue("kw", contains(filter.keyword()));
        }
        text(where, params, "p.province", "pickupProvince", filter.pickupProvince());
        text(where, params, "p.district", "pickupDistrict", filter.pickupDistrict());
        text(where, params, "d.province", "deliveryProvince", filter.deliveryProvince());
        text(where, params, "d.district", "deliveryDistrict", filter.deliveryDistrict());
        if (filter.vehicleTypeId() != null) { where.append(" AND s.vehicle_type_code = :vehicleType"); params.addValue("vehicleType", filter.vehicleTypeId()); }
        range(where, params, "s.estimated_price", "Price", filter.minPrice(), filter.maxPrice());
        range(where, params, "s.total_weight_kg", "Weight", filter.minWeight(), filter.maxWeight());
        if (filter.maxDistance() != null) { where.append(" AND q.distance_km <= :maxDistance"); params.addValue("maxDistance", filter.maxDistance()); }

        long total = jdbc.queryForObject("SELECT count(*) " + FROM + where, params, Long.class);
        String order = " ORDER BY " + filter.sort().column + (filter.ascending() ? " ASC NULLS LAST" : " DESC NULLS LAST") + ", s.id";
        params.addValue("limit", filter.size()).addValue("offset", (long) filter.page() * filter.size());
        List<VehicleTypeDto> types = catalogs.vehicleTypes();
        List<MarketplaceShipmentSummary> rows = jdbc.query(COLUMNS + FROM + where + order + " LIMIT :limit OFFSET :offset", params,
                (rs, i) -> summary(rs, types, cutoff));
        return new PageResponse<>(rows, filter.page(), filter.size(), total, (int) ((total + filter.size() - 1) / filter.size()));
    }

    /** Admin overview of orders that reached the marketplace, with application counts and the chosen driver. */
    public PageResponse<AdminMarketplaceShipment> adminShipments(ShipmentStatus status, String keyword, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE s.deleted_at IS NULL AND s.status <> 'DRAFT'");
        if (status != null) { where.append(" AND s.status = :status"); params.addValue("status", status.name()); }
        else where.append(" AND s.status IN ('PUBLISHED', 'ASSIGNED', 'EXPIRED', 'CANCELLED')");
        if (keyword != null && !keyword.isBlank()) { where.append(" AND s.shipment_code ILIKE :kw ESCAPE '\\'"); params.addValue("kw", contains(keyword.trim())); }
        String from = """
                 FROM shipments s
                 LEFT JOIN LATERAL (SELECT province, district, ward FROM shipment_stops
                                     WHERE shipment_id = s.id AND stop_type = 'PICKUP' ORDER BY sequence_no LIMIT 1) p ON TRUE
                 LEFT JOIN LATERAL (SELECT province, district, ward FROM shipment_stops
                                     WHERE shipment_id = s.id AND stop_type = 'DELIVERY' ORDER BY sequence_no DESC LIMIT 1) d ON TRUE
                 LEFT JOIN driver_profiles dp ON dp.id = s.assigned_driver_id
                 LEFT JOIN users u ON u.id = dp.user_id
                """;
        long total = jdbc.queryForObject("SELECT count(*) FROM shipments s" + where, params, Long.class);
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        String sql = """
                SELECT s.id, s.shipment_code, s.status, s.pickup_time, s.estimated_price, s.vehicle_count, s.assigned_driver_id, s.assigned_at, s.published_at,
                       u.full_name AS driver_name, p.province AS p_province, p.district AS p_district, p.ward AS p_ward,
                       d.province AS d_province, d.district AS d_district, d.ward AS d_ward,
                       (SELECT count(*) FROM driver_shipment_applications a WHERE a.shipment_id = s.id AND a.status = 'PENDING') AS pending,
                       (SELECT count(*) FROM driver_shipment_applications a WHERE a.shipment_id = s.id) AS total
                """ + from + where + " ORDER BY s.published_at DESC NULLS LAST, s.id LIMIT :limit OFFSET :offset";
        List<AdminMarketplaceShipment> rows = jdbc.query(sql, params, (rs, i) -> new AdminMarketplaceShipment(
                rs.getObject("id", UUID.class), rs.getString("shipment_code"), ShipmentStatus.valueOf(rs.getString("status")), instant(rs, "pickup_time"),
                area(rs, "p_"), area(rs, "d_"), rs.getBigDecimal("estimated_price"), rs.getInt("vehicle_count"), rs.getLong("pending"), rs.getLong("total"),
                rs.getObject("assigned_driver_id", UUID.class), rs.getString("driver_name"), instant(rs, "assigned_at"), instant(rs, "published_at")));
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    private static MarketplaceShipmentSummary summary(ResultSet rs, List<VehicleTypeDto> types, Duration cutoff) throws SQLException {
        String type = rs.getString("vehicle_type_code");
        Instant pickup = instant(rs, "pickup_time");
        return new MarketplaceShipmentSummary(rs.getObject("id", UUID.class), rs.getString("shipment_code"), pickup, instant(rs, "delivery_deadline"),
                pickup == null ? null : pickup.minus(cutoff), area(rs, "p_"), area(rs, "d_"), rs.getInt("delivery_count"), type,
                types.stream().filter(t -> t.code().equals(type)).map(VehicleTypeDto::name).findFirst().orElse(null), rs.getString("body_type_code"),
                rs.getInt("total_quantity"), rs.getBigDecimal("total_weight_kg"), rs.getBigDecimal("total_volume_m3"), rs.getBigDecimal("estimated_price"),
                rs.getBigDecimal("distance_km"), instant(rs, "published_at"));
    }

    private static void text(StringBuilder where, MapSqlParameterSource params, String column, String name, String value) {
        if (value == null) return;
        where.append(" AND unaccent(coalesce(").append(column).append(", '')) ILIKE unaccent(:").append(name).append(") ESCAPE '\\'");
        params.addValue(name, contains(value));
    }

    private static void range(StringBuilder where, MapSqlParameterSource params, String column, String name, Object min, Object max) {
        if (min != null) { where.append(" AND ").append(column).append(" >= :min").append(name); params.addValue("min" + name, min); }
        if (max != null) { where.append(" AND ").append(column).append(" <= :max").append(name); params.addValue("max" + name, max); }
    }

    /** LIKE pattern for "contains", with the user's own % and _ taken literally. */
    static String contains(String value) {
        return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static Area area(ResultSet rs, String prefix) throws SQLException {
        String province = rs.getString(prefix + "province");
        return province == null ? null : new Area(province, rs.getString(prefix + "district"), rs.getString(prefix + "ward"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
