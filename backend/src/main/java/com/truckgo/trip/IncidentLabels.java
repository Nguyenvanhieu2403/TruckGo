package com.truckgo.trip;

import java.util.Map;

final class IncidentLabels {
    private static final Map<Enum<?>, String> LABELS = Map.ofEntries(
            Map.entry(IncidentType.VEHICLE_BREAKDOWN, "hỏng xe"), Map.entry(IncidentType.TRAFFIC_JAM, "kẹt xe"), Map.entry(IncidentType.ACCIDENT, "tai nạn"),
            Map.entry(IncidentType.GOODS_DAMAGED, "hàng hư hỏng"), Map.entry(IncidentType.CUSTOMER_UNAVAILABLE, "không liên lạc được người nhận"),
            Map.entry(IncidentType.WRONG_ADDRESS, "sai địa chỉ"), Map.entry(IncidentType.WEATHER, "thời tiết xấu"), Map.entry(IncidentType.OTHER, "khác"),
            Map.entry(IncidentStatus.OPEN, "mới"), Map.entry(IncidentStatus.IN_PROGRESS, "đang xử lý"), Map.entry(IncidentStatus.RESOLVED, "đã xử lý"),
            Map.entry(IncidentStatus.CANCELLED, "đã hủy"));

    private IncidentLabels() { }

    static String label(Enum<?> value) { return LABELS.getOrDefault(value, value.name()); }
}
