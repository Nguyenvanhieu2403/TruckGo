package com.truckgo.shipment;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * List query. Dates are calendar days in the business time zone, inclusive on both ends. Sorting is limited to a
 * whitelist so arbitrary property names cannot reach the query.
 */
public record ShipmentFilter(ShipmentStatus status, String keyword, LocalDate fromDate, LocalDate toDate, int page, int size, String sortBy, String sortDirection) {
    private static final Map<String, String> SORTABLE = Map.of(
            "createdAt", "createdAt", "pickupTime", "pickupTime", "shipmentCode", "shipmentCode", "totalWeightKg", "totalWeightKg", "status", "status");
    public static final int MAX_SIZE = 100;

    public Pageable pageable() {
        String property = SORTABLE.getOrDefault(sortBy == null ? "createdAt" : sortBy, null);
        if (property == null) throw new IllegalArgumentException("Trường sắp xếp không hợp lệ");
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, property).and(Sort.by(Sort.Direction.DESC, "id"));
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE), sort);
    }

    public Specification<Shipment> specification(UUID customerId, ZoneId zone) {
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) throw new IllegalArgumentException("Khoảng ngày không hợp lệ");
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (customerId != null) where.add(cb.equal(root.get("customerId"), customerId));
            if (status != null) where.add(cb.equal(root.get("status"), status));
            if (fromDate != null) where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate.atStartOfDay(zone).toInstant()));
            if (toDate != null) where.add(cb.lessThan(root.get("createdAt"), toDate.plusDays(1).atStartOfDay(zone).toInstant()));
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                Subquery<UUID> stops = query.subquery(UUID.class);
                var stop = stops.from(ShipmentStop.class);
                stops.select(stop.get("shipment").get("id")).where(cb.or(
                        cb.like(cb.lower(stop.get("fullAddress")), like, '\\'),
                        cb.like(cb.lower(stop.get("contactName")), like, '\\')));
                where.add(cb.or(cb.like(cb.lower(root.get("shipmentCode")), like, '\\'), root.get("id").in(stops)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
