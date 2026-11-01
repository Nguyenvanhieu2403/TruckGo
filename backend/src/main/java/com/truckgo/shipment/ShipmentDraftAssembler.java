package com.truckgo.shipment;

import com.truckgo.catalog.CatalogService;
import com.truckgo.catalog.dto.CatalogDtos.ServiceDefinitionDto;
import com.truckgo.catalog.dto.CatalogDtos.ServiceOptionDto;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.geo.AddressFormatter;
import com.truckgo.shipment.dto.ShipmentRequests.DraftRequest;
import com.truckgo.shipment.dto.ShipmentRequests.ItemRequest;
import com.truckgo.shipment.dto.ShipmentRequests.ServiceRequest;
import com.truckgo.shipment.dto.ShipmentRequests.StopRequest;
import com.truckgo.user.ContactNormalizer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates a draft request against business rules and the catalogs, then writes it onto the aggregate. All errors
 * are collected and reported together as {@code data: {"stops[0].contactPhone": "..."}}.
 */
@Component
public class ShipmentDraftAssembler {
    private static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(365);

    private final CatalogService catalogs;
    private final Clock clock;

    public ShipmentDraftAssembler(CatalogService catalogs, Clock clock) {
        this.catalogs = catalogs;
        this.clock = clock;
    }

    /** Replaces the order's details; the caller has already cleared and flushed the previous children. */
    public void apply(Shipment shipment, DraftRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<ShipmentStop> stops = stops(request.stops(), errors);
        List<ShipmentItem> items = items(request.items(), errors);
        List<ShipmentExtraService> services = services(request.services() == null ? List.of() : request.services(), errors);
        Set<String> orderWide = new HashSet<>();
        services.forEach(x -> orderWide.add(x.getServiceCode()));
        for (ShipmentStop stop : stops)
            for (ShipmentStopService x : stop.getServices())
                if (orderWide.contains(x.getServiceCode()))
                    errors.put("services", x.getServiceName() + " đã được chọn cho cả đơn; hãy chọn theo đơn hoặc theo từng điểm, không chọn cả hai");
        Instant pickup = toInstant(request.pickupTime());
        Instant deadline = toInstant(request.deliveryDeadline());
        Instant now = clock.instant();
        if (pickup != null && pickup.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) errors.put("pickupTime", "Thời gian lấy hàng tối đa trong vòng 1 năm");
        if (pickup != null && deadline != null && !deadline.isAfter(pickup)) errors.put("deliveryDeadline", "Thời gian giao phải sau thời gian lấy hàng");
        if (deadline != null && pickup == null) errors.put("pickupTime", "Nhập thời gian lấy hàng trước khi đặt hạn giao");
        String vehicleType = code(request.vehicleTypeCode());
        if (vehicleType != null && catalogs.vehicleType(vehicleType).isEmpty()) errors.put("vehicleTypeCode", "Loại xe không hợp lệ");
        String bodyType = code(request.bodyTypeCode());
        if (bodyType != null && catalogs.bodyType(bodyType).isEmpty()) errors.put("bodyTypeCode", "Loại thùng xe không hợp lệ");
        if (!errors.isEmpty()) throw new FieldErrorException(HttpStatus.BAD_REQUEST, "Dữ liệu đơn hàng không hợp lệ", errors);

        shipment.setSchedule(pickup, deadline);
        shipment.setVehicleRequirement(vehicleType, bodyType, request.vehicleCount() == null ? 1 : request.vehicleCount(), request.minimumPayloadKg(),
                request.minCargoLengthCm(), request.minCargoWidthCm(), request.minCargoHeightCm());
        shipment.setCustomerNote(blankToNull(request.customerNote()));
        stops.forEach(shipment::addStop);
        items.forEach(shipment::addItem);
        services.forEach(shipment::addService);
        shipment.recalculateTotals();
    }

    /** Extra checks that only apply when the order goes live. */
    public void validateForPublish(Shipment shipment, Duration minLead) {
        Map<String, String> errors = new LinkedHashMap<>();
        Instant earliest = clock.instant().plus(minLead);
        if (shipment.getPickupTime() == null) errors.put("pickupTime", "Vui lòng chọn thời gian lấy hàng");
        else if (shipment.getPickupTime().isBefore(earliest))
            errors.put("pickupTime", "Thời gian lấy hàng phải sau thời điểm hiện tại ít nhất " + minLead.toMinutes() + " phút");
        if (shipment.getStops().stream().noneMatch(s -> s.getStopType() == StopType.PICKUP)) errors.put("stops", "Cần ít nhất một điểm lấy hàng");
        if (shipment.getStops().stream().noneMatch(s -> s.getStopType() == StopType.DELIVERY)) errors.put("stops", "Cần ít nhất một điểm giao hàng");
        if (shipment.getItems().isEmpty()) errors.put("items", "Cần ít nhất một loại hàng hóa");
        for (int i = 0; i < shipment.getItems().size(); i++) {
            ShipmentItem item = shipment.getItems().get(i);
            if (!item.hasDimensions()) errors.put("items[" + i + "].lengthCm", "Nhập đủ kích thước dài, rộng, cao trước khi đăng đơn");
            if (catalogs.itemCategory(item.getCategoryCode()).isEmpty()) errors.put("items[" + i + "].category", "Loại hàng không còn được hỗ trợ");
        }
        if (shipment.getTotalWeightKg().signum() <= 0) errors.put("items", "Tổng trọng lượng phải lớn hơn 0");
        if (shipment.getVehicleTypeCode() != null && catalogs.vehicleType(shipment.getVehicleTypeCode()).isEmpty())
            errors.put("vehicleTypeCode", "Loại xe không còn được hỗ trợ, vui lòng chọn lại");
        for (ShipmentExtraService service : shipment.getServices())
            if (catalogs.shipmentService(service.getServiceCode()).isEmpty()) errors.put("services", "Dịch vụ " + service.getServiceName() + " không còn được hỗ trợ");
        if (!errors.isEmpty()) throw new FieldErrorException(HttpStatus.BAD_REQUEST, "Đơn hàng chưa đủ thông tin để đăng", errors);
    }

    private List<ShipmentStop> stops(List<StopRequest> requests, Map<String, String> errors) {
        // Work on original indices so error keys always point at the request element the customer sent.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) order.add(i);
        boolean explicitOrder = requests.stream().allMatch(s -> s.sequenceNo() != null)
                && requests.stream().map(StopRequest::sequenceNo).distinct().count() == requests.size();
        if (explicitOrder) order.sort(Comparator.comparing(i -> requests.get(i).sequenceNo()));
        if (requests.stream().noneMatch(s -> s.stopType() == StopType.PICKUP)) errors.put("stops", "Cần ít nhất một điểm lấy hàng");
        else if (requests.stream().noneMatch(s -> s.stopType() == StopType.DELIVERY)) errors.put("stops", "Cần ít nhất một điểm giao hàng");
        else if (requests.get(order.get(0)).stopType() != StopType.PICKUP) errors.put("stops", "Điểm dừng đầu tiên phải là điểm lấy hàng");

        List<ShipmentStop> stops = new ArrayList<>();
        for (int position = 0; position < order.size(); position++) {
            int index = order.get(position);
            StopRequest s = requests.get(index);
            String prefix = "stops[" + index + "].";
            String phone = null;
            try { phone = ContactNormalizer.phone(s.contactPhone()); }
            catch (IllegalArgumentException ex) { errors.put(prefix + "contactPhone", "Số điện thoại di động không hợp lệ"); }
            if ((s.latitude() == null) != (s.longitude() == null)) errors.put(prefix + "latitude", "Nhập đủ cả vĩ độ và kinh độ hoặc để trống cả hai");
            String full = AddressFormatter.format(s.addressDetail(), s.ward(), s.district(), s.province());
            // Blank coordinates are filled later, in the background, by StopCoordinateFiller (never while saving).
            BigDecimal lat = s.latitude();
            BigDecimal lng = s.longitude();
            ShipmentStop stop = new ShipmentStop(s.stopType(), position + 1, s.contactName().trim(), phone, s.province().trim(), blankToNull(s.district()),
                    blankToNull(s.ward()), s.addressDetail().trim(), full, lat, lng, blankToNull(s.note()));
            stopServices(stop, s.services(), prefix, errors);
            stops.add(stop);
        }
        return stops;
    }

    private List<ShipmentItem> items(List<ItemRequest> requests, Map<String, String> errors) {
        List<ShipmentItem> items = new ArrayList<>();
        Instant base = clock.instant();
        for (int i = 0; i < requests.size(); i++) {
            ItemRequest r = requests.get(i);
            String category = code(r.category());
            if (category == null || catalogs.itemCategory(category).isEmpty()) errors.put("items[" + i + "].category", "Loại hàng không hợp lệ");
            int dims = (r.lengthCm() == null ? 0 : 1) + (r.widthCm() == null ? 0 : 1) + (r.heightCm() == null ? 0 : 1);
            if (dims != 0 && dims != 3) errors.put("items[" + i + "].lengthCm", "Nhập đủ dài, rộng, cao hoặc để trống cả ba");
            // Distinct timestamps keep the customer's line order stable (items are ordered by created_at).
            items.add(new ShipmentItem(r.itemName().trim(), category, blankToNull(r.description()), r.quantity(), r.weightPerUnitKg(),
                    dims == 3 ? r.lengthCm() : null, dims == 3 ? r.widthCm() : null, dims == 3 ? r.heightCm() : null, base.plusMillis(i)));
        }
        return items;
    }

    private List<ShipmentExtraService> services(List<ServiceRequest> requests, Map<String, String> errors) {
        List<ShipmentExtraService> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, String> exclusive = new HashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            ServiceRequest r = requests.get(i);
            String key = "services[" + i + "].serviceValue";
            String code = code(r.serviceCode());
            Optional<ServiceDefinitionDto> found = catalogs.shipmentService(code);
            if (found.isEmpty()) { errors.put("services[" + i + "].serviceCode", "Dịch vụ không hợp lệ"); continue; }
            ServiceDefinitionDto def = found.get();
            if (!seen.add(code)) { errors.put("services[" + i + "].serviceCode", "Dịch vụ bị chọn trùng"); continue; }
            if (!def.orderLevel()) { errors.put("services[" + i + "].serviceCode", def.name() + " chỉ chọn được cho từng điểm dừng"); continue; }
            String stored = normalizeValue(def, r.serviceValue(), key, errors);
            if (stored == null) continue;
            if (def.exclusiveGroup() != null) {
                String other = exclusive.putIfAbsent(def.exclusiveGroup(), def.name());
                if (other != null) { errors.put("services[" + i + "].serviceCode", def.name() + " không thể chọn cùng " + other); continue; }
            }
            result.add(new ShipmentExtraService(def.code(), def.name(), stored, def.description()));
        }
        return result;
    }

    /** The value to store for a selected service, or null when it is not selected or invalid (error recorded). */
    private static String normalizeValue(ServiceDefinitionDto def, String raw, String key, Map<String, String> errors) {
        String value = raw == null ? null : raw.trim();
        switch (def.valueType()) {
            case BOOLEAN -> {
                if ("false".equalsIgnoreCase(value)) return null; // explicitly not selected
                if (value != null && !value.isEmpty() && !"true".equalsIgnoreCase(value)) { errors.put(key, "Giá trị phải là true/false"); return null; }
                return "true";
            }
            case OPTION -> {
                if (def.options().stream().map(ServiceOptionDto::value).noneMatch(v -> v.equals(value))) { errors.put(key, "Lựa chọn không hợp lệ cho " + def.name()); return null; }
                return value;
            }
            case NUMBER -> {
                Integer n = parseInt(value);
                if (n == null || (def.minValue() != null && n < def.minValue()) || (def.maxValue() != null && n > def.maxValue())) {
                    errors.put(key, def.name() + ": nhập số nguyên từ " + def.minValue() + " đến " + def.maxValue());
                    return null;
                }
                return n.toString();
            }
            case TEXT -> {
                if (value == null || value.isEmpty()) { errors.put(key, "Vui lòng nhập nội dung cho " + def.name()); return null; }
                return value;
            }
            default -> throw new IllegalStateException();
        }
    }

    /** Services ordered at one stop: allowed for that kind of stop, valid value, at most once per stop. */
    private void stopServices(ShipmentStop stop, List<ServiceRequest> requests, String prefix, Map<String, String> errors) {
        if (requests == null) return;
        Set<String> seen = new HashSet<>();
        for (int j = 0; j < requests.size(); j++) {
            ServiceRequest r = requests.get(j);
            String key = prefix + "services[" + j + "]";
            String code = code(r.serviceCode());
            Optional<ServiceDefinitionDto> found = catalogs.shipmentService(code);
            if (found.isEmpty()) { errors.put(key + ".serviceCode", "Dịch vụ không hợp lệ"); continue; }
            ServiceDefinitionDto def = found.get();
            if (!def.allowedAt(stop.getStopType())) {
                errors.put(key + ".serviceCode", def.name() + " không áp dụng cho " + (stop.getStopType() == StopType.PICKUP ? "điểm lấy hàng" : "điểm giao hàng"));
                continue;
            }
            if (!seen.add(code)) { errors.put(key + ".serviceCode", "Dịch vụ bị chọn trùng tại điểm này"); continue; }
            String stored = normalizeValue(def, r.serviceValue(), key + ".serviceValue", errors);
            if (stored != null) stop.addService(new ShipmentStopService(def.code(), def.name(), stored));
        }
    }

    private static Instant toInstant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }

    private static String code(String value) { return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT); }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static Integer parseInt(String value) {
        try { return value == null ? null : Integer.valueOf(value); }
        catch (NumberFormatException ex) { return null; }
    }
}
