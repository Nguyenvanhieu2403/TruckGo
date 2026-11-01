package com.truckgo.pricing.engine;

import com.truckgo.common.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Pure pricing function (no I/O). Formula:
 * <pre>
 *   total = base + distance + weight + volume + services + extra stops + time windows + other fees (+ minimum top-up, + manual)
 *   base      = basePrice × vehicles
 *   distance  = max(0, km − includedKm) × vehicles × pricePerKm
 *   weight    = max(0, totalKg − includedKg × vehicles) × pricePerExtraKg
 *   volume    = max(0, totalM3 − includedM3 × vehicles) × pricePerExtraM3
 *   stops     = max(0, stops − 2) × additionalStopPrice
 *   percent fees are a percentage of the transport charge (base + distance)
 * </pre>
 * Each line is rounded to whole VND (HALF_UP) and the total is the sum of the rounded lines, so the breakdown always
 * adds up exactly.
 */
public final class PricingCalculator {
    public static final BigDecimal MAX_TOTAL = new BigDecimal("100000000000"); // 100 billion VND sanity cap
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PricingCalculator() { }

    public static PricingResult calculate(PricingRates r, PricingInput in) {
        validate(in);
        List<QuoteLine> lines = new ArrayList<>();
        BigDecimal vehicles = BigDecimal.valueOf(in.vehicleCount());

        QuoteLine base = line(PriceComponent.BASE, "BASE", "Giá mở cửa", in.vehicleCount() > 1 ? in.vehicleCount() + " xe" : null, vehicles, r.basePrice());
        lines.add(base);

        BigDecimal billableKm = in.distanceKm().subtract(r.includedDistanceKm()).max(BigDecimal.ZERO);
        String distanceNote = r.includedDistanceKm().signum() > 0
                ? plain(in.distanceKm()) + " km, đã gồm " + plain(r.includedDistanceKm()) + " km trong giá mở cửa" : plain(in.distanceKm()) + " km";
        QuoteLine distance = line(PriceComponent.DISTANCE, "DISTANCE", "Cước theo quãng đường", distanceNote, billableKm.multiply(vehicles), r.pricePerKm());
        lines.add(distance);
        BigDecimal transport = base.amount().add(distance.amount());

        BigDecimal extraKg = in.totalWeightKg().subtract(r.includedWeightKg().multiply(vehicles)).max(BigDecimal.ZERO);
        if (extraKg.signum() > 0 && r.pricePerExtraKg().signum() > 0)
            lines.add(line(PriceComponent.WEIGHT, "WEIGHT", "Phụ phí khối lượng", "Vượt " + plain(r.includedWeightKg().multiply(vehicles)) + " kg", extraKg, r.pricePerExtraKg()));

        BigDecimal extraM3 = in.totalVolumeM3().subtract(r.includedVolumeM3().multiply(vehicles)).max(BigDecimal.ZERO);
        if (extraM3.signum() > 0 && r.pricePerExtraM3().signum() > 0)
            lines.add(line(PriceComponent.VOLUME, "VOLUME", "Phụ phí thể tích", "Vượt " + plain(r.includedVolumeM3().multiply(vehicles)) + " m³", extraM3, r.pricePerExtraM3()));

        for (PricingInput.SelectedService s : in.services()) lines.add(service(r, in, s, transport));
        // Per-stop services: one line per service and option, quantity = number of stops it was ordered at.
        Map<String, List<PricingInput.StopService>> byService = new LinkedHashMap<>();
        for (PricingInput.StopService s : in.stopServices()) byService.computeIfAbsent(s.code() + "\u0000" + s.value(), k -> new ArrayList<>()).add(s);
        for (List<PricingInput.StopService> group : byService.values()) lines.add(stopService(r, group, transport));

        int extraStops = Math.max(0, in.stopCount() - 2);
        if (extraStops > 0 && r.additionalStopPrice().signum() > 0)
            lines.add(line(PriceComponent.STOP, "EXTRA_STOP", "Phụ phí điểm dừng bổ sung", extraStops + " điểm ngoài 1 điểm lấy và 1 điểm giao",
                    BigDecimal.valueOf(extraStops), r.additionalStopPrice()));

        if (in.pickupLocalTime() != null)
            for (PricingRates.TimeRule rule : r.timeRules())
                if (matches(rule, in.pickupLocalTime())) lines.add(fee(PriceComponent.TIME, "TIME:" + rule.name(), rule.name(), rule.type(), rule.amount(), rule.percentage(), transport));

        for (PricingRates.ExtraFee extra : r.extraFees())
            lines.add(fee(PriceComponent.OTHER, "FEE:" + extra.code(), extra.name(), extra.type(), extra.amount(), extra.percentage(), transport));

        BigDecimal subtotal = sum(lines);
        if (subtotal.compareTo(r.minimumPrice()) < 0) {
            BigDecimal topUp = round(r.minimumPrice().subtract(subtotal));
            lines.add(new QuoteLine(PriceComponent.OTHER, "MINIMUM_PRICE", "Bù giá tối thiểu", "Giá tối thiểu " + plain(r.minimumPrice()) + " VNĐ", BigDecimal.ONE, topUp, topUp));
        }
        if (in.manualAdjustment() != null && in.manualAdjustment().signum() != 0) {
            BigDecimal adj = round(in.manualAdjustment());
            lines.add(new QuoteLine(PriceComponent.OTHER, "MANUAL_ADJUSTMENT", "Điều chỉnh của quản trị viên", null, BigDecimal.ONE, adj, adj));
        }
        PricingResult result = PricingResult.of(lines);
        if (result.total().signum() < 0) throw new BusinessException("Tổng giá sau điều chỉnh không được âm");
        if (result.total().compareTo(MAX_TOTAL) > 0) throw new BusinessException("Tổng giá vượt giới hạn cho phép");
        return result;
    }

    private static QuoteLine service(PricingRates r, PricingInput in, PricingInput.SelectedService s, BigDecimal transport) {
        Optional<PricingRates.ServiceFee> fee = r.serviceFees().stream().filter(f -> f.serviceCode().equals(s.code()) && s.value() != null && s.value().equals(f.optionValue())).findFirst()
                .or(() -> r.serviceFees().stream().filter(f -> f.serviceCode().equals(s.code()) && f.optionValue() == null).findFirst());
        String code = "SERVICE:" + s.code();
        if (fee.isEmpty() || fee.get().type() == FeeType.FREE)
            return new QuoteLine(PriceComponent.SERVICE, code, s.name(), "Miễn phí", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
        PricingRates.ServiceFee f = fee.get();
        return switch (f.type()) {
            case FIXED -> line(PriceComponent.SERVICE, code, s.name(), null, BigDecimal.ONE, f.amount());
            case PERCENTAGE -> percentLine(PriceComponent.SERVICE, code, s.name(), f.percentage(), transport);
            case PER_UNIT -> {
                BigDecimal units = s.numeric() && s.value() != null ? new BigDecimal(s.value()) : BigDecimal.valueOf(in.totalQuantity());
                yield line(PriceComponent.SERVICE, code, s.name(), s.numeric() ? "Theo số lượng khai báo" : "Theo số kiện", units, f.amount());
            }
            case PER_STOP -> line(PriceComponent.SERVICE, code, s.name(), "Theo số điểm dừng", BigDecimal.valueOf(in.stopCount()), f.amount());
            case FREE -> throw new IllegalStateException();
        };
    }

    /**
     * The fee of one occurrence (the order-level rule applied to a single stop) times the number of stops. FIXED and
     * PER_STOP are an amount per stop, PERCENTAGE a share of the transport price per stop, PER_UNIT the amount per
     * declared unit (1 when the service has no number) per stop.
     */
    private static QuoteLine stopService(PricingRates r, List<PricingInput.StopService> group, BigDecimal transport) {
        PricingInput.StopService first = group.getFirst();
        List<Integer> sequences = group.stream().map(PricingInput.StopService::sequenceNo).sorted().toList();
        BigDecimal count = BigDecimal.valueOf(sequences.size());
        String where = "Tại điểm " + sequences.stream().map(String::valueOf).collect(Collectors.joining(", "));
        String code = "STOP_SERVICE:" + first.code() + (first.value() == null ? "" : ":" + first.value());
        Optional<PricingRates.ServiceFee> fee = r.serviceFees().stream().filter(f -> f.serviceCode().equals(first.code()) && first.value() != null && first.value().equals(f.optionValue())).findFirst()
                .or(() -> r.serviceFees().stream().filter(f -> f.serviceCode().equals(first.code()) && f.optionValue() == null).findFirst());
        BigDecimal unit;
        if (fee.isEmpty() || fee.get().type() == FeeType.FREE) {
            return new QuoteLine(PriceComponent.SERVICE, code, first.name(), where + " · Miễn phí", count, BigDecimal.ZERO, BigDecimal.ZERO, "STOP", sequences);
        }
        PricingRates.ServiceFee f = fee.get();
        unit = switch (f.type()) {
            case FIXED, PER_STOP -> f.amount();
            case PERCENTAGE -> round(transport.multiply(f.percentage()).divide(HUNDRED, 6, RoundingMode.HALF_UP));
            case PER_UNIT -> f.amount().multiply(first.numeric() && first.value() != null ? new BigDecimal(first.value()) : BigDecimal.ONE);
            case FREE -> BigDecimal.ZERO;
        };
        String detail = f.type() == FeeType.PERCENTAGE ? where + " · " + plain(f.percentage()) + "% cước vận chuyển mỗi điểm" : where;
        return new QuoteLine(PriceComponent.SERVICE, code, first.name(), detail, count, round(unit), round(count.multiply(round(unit))), "STOP", sequences);
    }

    private static QuoteLine fee(PriceComponent component, String code, String name, FeeType type, BigDecimal amount, BigDecimal percentage, BigDecimal transport) {
        return type == FeeType.PERCENTAGE ? percentLine(component, code, name, percentage, transport) : line(component, code, name, null, BigDecimal.ONE, amount);
    }

    private static QuoteLine percentLine(PriceComponent component, String code, String name, BigDecimal percentage, BigDecimal transport) {
        BigDecimal amount = round(transport.multiply(percentage).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        return new QuoteLine(component, code, name, plain(percentage) + "% cước vận chuyển", BigDecimal.ONE, amount, amount);
    }

    private static QuoteLine line(PriceComponent component, String code, String name, String description, BigDecimal quantity, BigDecimal unitPrice) {
        return new QuoteLine(component, code, name, description, quantity, unitPrice, round(quantity.multiply(unitPrice)));
    }

    static boolean matches(PricingRates.TimeRule rule, java.time.LocalDateTime when) {
        LocalTime t = when.toLocalTime();
        boolean anyDay = rule.days() == null || rule.days().isEmpty();
        if (rule.start().isBefore(rule.end()))
            return (anyDay || rule.days().contains(when.getDayOfWeek())) && !t.isBefore(rule.start()) && t.isBefore(rule.end());
        // Overnight window: the part after midnight belongs to the day the window started.
        if (!t.isBefore(rule.start())) return anyDay || rule.days().contains(when.getDayOfWeek());
        return t.isBefore(rule.end()) && (anyDay || rule.days().contains(when.getDayOfWeek().minus(1)));
    }

    private static void validate(PricingInput in) {
        if (in.distanceKm() == null || in.distanceKm().signum() <= 0) throw new BusinessException("Quãng đường phải lớn hơn 0");
        if (in.vehicleCount() < 1) throw new BusinessException("Số xe không hợp lệ");
        if (in.totalWeightKg().signum() < 0 || in.totalVolumeM3().signum() < 0 || in.totalQuantity() < 0 || in.stopCount() < 2)
            throw new BusinessException("Dữ liệu hàng hóa hoặc điểm dừng không hợp lệ");
    }

    private static BigDecimal sum(List<QuoteLine> lines) { return lines.stream().map(QuoteLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add); }

    /** Whole VND, half-up. */
    public static BigDecimal round(BigDecimal value) { return value.setScale(0, RoundingMode.HALF_UP); }

    /** Vietnamese number style for line descriptions shown to customers: 1.234,5 */
    static String plain(BigDecimal v) {
        java.text.DecimalFormat format = new java.text.DecimalFormat("#,##0.######", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.forLanguageTag("vi-VN")));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(v);
    }
}
