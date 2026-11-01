package com.truckgo.pricing.quote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.catalog.CatalogService;
import com.truckgo.catalog.ServiceValueType;
import com.truckgo.catalog.dto.CatalogDtos.VehicleTypeDto;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.pricing.config.ActivePricingCache.ActiveRates;
import com.truckgo.pricing.config.PricingConfigService;
import com.truckgo.pricing.engine.PricingCalculator;
import com.truckgo.pricing.engine.PricingInput;
import com.truckgo.pricing.engine.PricingResult;
import com.truckgo.pricing.route.RouteDistanceResult;
import com.truckgo.pricing.route.RouteDistanceService;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentProperties;
import com.truckgo.shipment.StopType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds one quote version from the stored order. Never invents data: without a reliable distance, a matching price
 * table or a vehicle big enough for the cargo, the quote is created as AWAITING_MANUAL_REVIEW with the reason.
 */
@Component
public class QuoteFactory {
    /** How the distance of a new version is obtained. */
    public record DistanceInput(BigDecimal verifiedKm, String source) {
        static DistanceInput fromProvider() { return new DistanceInput(null, null); }
        static DistanceInput manual(BigDecimal km) { return new DistanceInput(km, "MANUAL"); }
    }

    private final RouteDistanceService routes;
    private final PricingConfigService pricing;
    private final CatalogService catalogs;
    private final ShipmentProperties shipmentProperties;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration validity;

    public QuoteFactory(RouteDistanceService routes, PricingConfigService pricing, CatalogService catalogs, ShipmentProperties shipmentProperties,
                        ObjectMapper json, Clock clock, @Value("${app.pricing.quote-validity:24h}") Duration validity) {
        this.routes = routes;
        this.pricing = pricing;
        this.catalogs = catalogs;
        this.shipmentProperties = shipmentProperties;
        this.json = json;
        this.clock = clock;
        this.validity = validity;
    }

    /** Order data that must exist before any price can be computed; reported to the customer as field errors. */
    public void validateShipment(Shipment s) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (s.getStops().stream().noneMatch(x -> x.getStopType() == StopType.PICKUP) || s.getStops().stream().noneMatch(x -> x.getStopType() == StopType.DELIVERY))
            errors.put("stops", "Đơn cần có điểm lấy và điểm giao hàng");
        if (s.getItems().isEmpty() || s.getTotalWeightKg().signum() <= 0) errors.put("items", "Đơn cần có hàng hóa với trọng lượng lớn hơn 0");
        if (s.getItems().stream().anyMatch(i -> !i.hasDimensions())) errors.put("items", "Bổ sung kích thước cho tất cả hàng hóa trước khi báo giá");
        if (s.getPickupTime() == null) errors.put("pickupTime", "Chọn thời gian lấy hàng trước khi báo giá");
        if (!errors.isEmpty()) throw new FieldErrorException(HttpStatus.BAD_REQUEST, "Đơn hàng chưa đủ thông tin để báo giá", errors);
    }

    /**
     * Hash of what a provider-priced quote depends on: stops (order, address, coordinates, stop services), cargo,
     * services, pickup time, vehicle requirement and choice, the exact rates of the applicable price table and the
     * route provider. Any change yields a different fingerprint, and therefore a new version when re-priced.
     */
    public String fingerprint(Shipment s) {
        Instant now = clock.instant();
        VehicleChoice vehicle = chooseVehicle(s, now);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("stops", s.getStops().stream().sorted(Comparator.comparingInt(com.truckgo.shipment.ShipmentStop::getSequenceNo))
                .map(x -> x.getSequenceNo() + "|" + x.getStopType() + "|" + x.getFullAddress() + "|" + x.getLatitude() + "|" + x.getLongitude() + "|"
                        + x.getServices().stream().map(v -> v.getServiceCode() + "=" + v.getServiceValue()).sorted().toList()).toList());
        f.put("cargo", List.of(s.getTotalWeightKg().toPlainString(), s.getTotalVolumeM3().toPlainString(), s.getTotalQuantity()));
        f.put("services", s.getServices().stream().map(x -> x.getServiceCode() + "=" + x.getServiceValue()).sorted().toList());
        f.put("pickupTime", String.valueOf(s.getPickupTime()));
        f.put("vehicle", List.of(String.valueOf(s.getVehicleTypeCode()), s.getVehicleCount(), String.valueOf(s.getMinimumPayloadKg()), String.valueOf(vehicle.code())));
        f.put("rates", vehicle.code() == null ? null : pricing.effectiveFor(vehicle.code(), now).map(ActiveRates::rates).orElse(null));
        f.put("route", routes.name());
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(f)));
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public ShipmentQuote build(Shipment s, int version, UUID actor, DistanceInput distanceInput, BigDecimal manualAdjustment, UUID adjustedBy, String adjustmentReason) {
        Instant now = clock.instant();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("input", Map.of("totalWeightKg", s.getTotalWeightKg(), "totalVolumeM3", s.getTotalVolumeM3(), "totalQuantity", s.getTotalQuantity(),
                "stopCount", s.getStops().size(), "vehicleCount", s.getVehicleCount(), "requestedVehicleTypeCode", String.valueOf(s.getVehicleTypeCode()),
                "pickupTime", String.valueOf(s.getPickupTime()), "services", s.getServices().stream().map(x -> x.getServiceCode() + "=" + x.getServiceValue()).toList(),
                "stopServices", s.getStops().stream().flatMap(stop -> stop.getServices().stream()
                        .map(x -> stop.getSequenceNo() + ":" + stop.getStopType() + ":" + x.getServiceCode() + "=" + x.getServiceValue())).toList()));

        VehicleChoice vehicle = chooseVehicle(s, now);
        String fingerprint = distanceInput.verifiedKm() == null && manualAdjustment == null ? fingerprint(s) : null;
        ShipmentQuote quote = new ShipmentQuote(s.getId(), s.getShipmentCode(), version, actor, vehicle.code(), s.getVehicleCount());
        quote.fingerprint(fingerprint);   // only provider-priced versions can be reused; admin-adjusted ones never are
        if (adjustedBy != null) quote.adjustment(adjustedBy, adjustmentReason);
        snapshot.put("vehicle", Map.of("code", String.valueOf(vehicle.code()), "autoSelected", vehicle.autoSelected()));

        // Distance: a verified value from an admin, otherwise the route provider.
        RouteDistanceResult route;
        if (distanceInput.verifiedKm() != null) {
            route = RouteDistanceResult.of(distanceInput.verifiedKm(), null, "manual", null);
            quote.distance(distanceInput.verifiedKm(), "MANUAL", "manual", null);
        } else {
            route = routes.calculateDistance(s.getStops());
            if (route.available()) quote.distance(route.distanceKm(), "PROVIDER", route.provider(), route.durationMinutes());
        }
        snapshot.put("route", route);

        if (vehicle.problem() != null) return finish(quote, snapshot, () -> quote.awaitReview(vehicle.problemCode(), vehicle.problem()));
        if (!route.available()) return finish(quote, snapshot, () -> quote.awaitReview(route.reasonCode(), route.message()));
        Optional<ActiveRates> rates = pricing.effectiveFor(vehicle.code(), now);
        if (rates.isEmpty())
            return finish(quote, snapshot, () -> quote.awaitReview("NO_PRICING_CONFIG", "Chưa có bảng giá đang hiệu lực cho loại xe này; quản trị viên sẽ xử lý"));

        PricingInput input = new PricingInput(route.distanceKm(), s.getVehicleCount(), s.getTotalWeightKg(), s.getTotalVolumeM3(), s.getTotalQuantity(), s.getStops().size(),
                LocalDateTime.ofInstant(s.getPickupTime(), shipmentProperties.businessZone()),
                s.getServices().stream().map(x -> new PricingInput.SelectedService(x.getServiceCode(), x.getServiceName(),
                        catalogs.shipmentService(x.getServiceCode()).map(d -> d.valueType() == ServiceValueType.NUMBER).orElse(false), x.getServiceValue())).toList(),
                manualAdjustment,
                s.getStops().stream().flatMap(stop -> stop.getServices().stream().map(x -> new PricingInput.StopService(stop.getSequenceNo(), stop.getStopType().name(),
                        x.getServiceCode(), x.getServiceName(), catalogs.shipmentService(x.getServiceCode()).map(d -> d.valueType() == ServiceValueType.NUMBER).orElse(false),
                        x.getServiceValue()))).toList());
        PricingResult result = PricingCalculator.calculate(rates.get().rates(), input);
        snapshot.put("rates", rates.get().rates());
        snapshot.put("pricingInput", input);
        return finish(quote, snapshot, () -> quote.priced(rates.get().rates().configId(), result, now.plus(validity)));
    }

    private ShipmentQuote finish(ShipmentQuote quote, Map<String, Object> snapshot, Runnable outcome) {
        outcome.run();
        try { quote.snapshot(json.writeValueAsString(snapshot)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
        return quote;
    }

    private record VehicleChoice(String code, boolean autoSelected, String problemCode, String problem) { }

    /**
     * The requested vehicle type, or — when the customer let the system choose — the smallest catalog type with a
     * price table whose nominal payload covers the cargo. Nominal payload only; the real vehicle is checked at matching.
     */
    private VehicleChoice chooseVehicle(Shipment s, Instant now) {
        BigDecimal needed = s.getTotalWeightKg().max(Optional.ofNullable(s.getMinimumPayloadKg()).orElse(BigDecimal.ZERO));
        BigDecimal count = BigDecimal.valueOf(s.getVehicleCount());
        if (s.getVehicleTypeCode() != null) {
            Optional<VehicleTypeDto> type = catalogs.vehicleType(s.getVehicleTypeCode());
            if (type.isEmpty()) return new VehicleChoice(s.getVehicleTypeCode(), false, "VEHICLE_TYPE_UNAVAILABLE", "Loại xe đã chọn không còn được hỗ trợ");
            if (type.get().maxPayloadKg().multiply(count).compareTo(needed) < 0)
                return new VehicleChoice(s.getVehicleTypeCode(), false, "VEHICLE_TOO_SMALL",
                        "Tải trọng danh nghĩa của loại xe đã chọn nhỏ hơn khối lượng hàng; quản trị viên sẽ xem xét");
            return new VehicleChoice(s.getVehicleTypeCode(), false, null, null);
        }
        Set<String> priced = pricing.pricedVehicleTypes(now);
        List<VehicleTypeDto> suitable = catalogs.vehicleTypes().stream()
                .filter(v -> priced.contains(v.code()) && v.maxPayloadKg().multiply(count).compareTo(needed) >= 0)
                .sorted(Comparator.comparing(VehicleTypeDto::maxPayloadKg)).toList();
        if (suitable.isEmpty())
            return new VehicleChoice(null, true, "NO_SUITABLE_VEHICLE_PRICING", "Chưa có bảng giá cho loại xe phù hợp với khối lượng hàng; quản trị viên sẽ xử lý");
        return new VehicleChoice(suitable.get(0).code(), true, null, null);
    }
}
