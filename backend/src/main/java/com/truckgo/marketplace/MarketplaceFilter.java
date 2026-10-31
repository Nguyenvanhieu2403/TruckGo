package com.truckgo.marketplace;

import java.math.BigDecimal;

/**
 * Driver marketplace filter. Text filters match without accents or case ("ha noi" finds "Hà Nội"); sortBy is
 * whitelisted, so no request value reaches SQL except as a bind parameter.
 */
public record MarketplaceFilter(String keyword, String pickupProvince, String pickupDistrict, String deliveryProvince, String deliveryDistrict,
                                String vehicleTypeId, BigDecimal minPrice, BigDecimal maxPrice, BigDecimal minWeight, BigDecimal maxWeight,
                                BigDecimal maxDistance, String sortBy, String sortDirection, int page, int size) {
    public static final int MAX_SIZE = 50;

    public enum Sort {
        PICKUP_TIME("s.pickup_time"), PRICE("s.estimated_price"), DISTANCE("q.distance_km"), WEIGHT("s.total_weight_kg"), PUBLISHED_AT("s.published_at");

        final String column;

        Sort(String column) { this.column = column; }
    }

    public MarketplaceFilter {
        keyword = clean(keyword);
        pickupProvince = clean(pickupProvince);
        pickupDistrict = clean(pickupDistrict);
        deliveryProvince = clean(deliveryProvince);
        deliveryDistrict = clean(deliveryDistrict);
        vehicleTypeId = clean(vehicleTypeId);
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) throw new IllegalArgumentException("Giá tối thiểu phải nhỏ hơn giá tối đa");
        if (minWeight != null && maxWeight != null && minWeight.compareTo(maxWeight) > 0) throw new IllegalArgumentException("Khối lượng tối thiểu phải nhỏ hơn khối lượng tối đa");
    }

    public Sort sort() {
        if (sortBy == null || sortBy.isBlank()) return Sort.PICKUP_TIME;
        String key = sortBy.trim().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
        try { return Sort.valueOf(key); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("sortBy không hợp lệ: " + sortBy); }
    }

    /** Pickup time ascending by default (soonest first); everything else newest/largest first unless asked. */
    public boolean ascending() {
        if (sortDirection == null || sortDirection.isBlank()) return sort() == Sort.PICKUP_TIME;
        return switch (sortDirection.trim().toLowerCase()) {
            case "asc" -> true;
            case "desc" -> false;
            default -> throw new IllegalArgumentException("sortDirection phải là asc hoặc desc");
        };
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }
}
