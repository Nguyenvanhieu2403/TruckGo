package com.truckgo.pricing;

import com.truckgo.common.exception.BusinessException;
import com.truckgo.pricing.engine.FeeType;
import com.truckgo.pricing.engine.PriceComponent;
import com.truckgo.pricing.engine.PricingCalculator;
import com.truckgo.pricing.engine.PricingInput;
import com.truckgo.pricing.engine.PricingInput.SelectedService;
import com.truckgo.pricing.engine.PricingRates;
import com.truckgo.pricing.engine.PricingResult;
import com.truckgo.pricing.engine.QuoteLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingCalculatorTest {
    private static BigDecimal d(String v) { return new BigDecimal(v); }

    /** Spec example: base 100k, 8k/km, 500 kg included then 500/kg, loading 30k, extra stop 20k. */
    static PricingRates rates(List<PricingRates.ServiceFee> fees, List<PricingRates.TimeRule> time, List<PricingRates.ExtraFee> extra, String minimum) {
        return new PricingRates(UUID.randomUUID(), "Bảng giá thử", "TRUCK_1T", d("100000"), d("8000"), d("0"), d("500"), d("500"), d("5"), d("40000"),
                d("20000"), d(minimum), "VND", fees, time, extra);
    }

    static PricingRates rates() { return rates(List.of(new PricingRates.ServiceFee("LOADING_SUPPORT", null, FeeType.FIXED, d("30000"), d("0"))), List.of(), List.of(), "0"); }

    static PricingInput input(String km, String kg, String m3, int stops, List<SelectedService> services) {
        return new PricingInput(d(km), 1, d(kg), d(m3), 10, stops, null, services, null);
    }

    private static BigDecimal sumLines(PricingResult r) { return r.lines().stream().map(QuoteLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add); }

    @Test void specExampleTotals360k() {
        PricingResult r = PricingCalculator.calculate(rates(), input("20", "600", "2", 3, List.of(new SelectedService("LOADING_SUPPORT", "Hỗ trợ bốc hàng", false, "TWO_PEOPLE"))));
        assertThat(r.component(PriceComponent.BASE)).isEqualByComparingTo("100000");
        assertThat(r.component(PriceComponent.DISTANCE)).isEqualByComparingTo("160000");
        assertThat(r.component(PriceComponent.WEIGHT)).isEqualByComparingTo("50000");
        assertThat(r.component(PriceComponent.VOLUME)).isEqualByComparingTo("0");
        assertThat(r.component(PriceComponent.SERVICE)).isEqualByComparingTo("30000");
        assertThat(r.component(PriceComponent.STOP)).isEqualByComparingTo("20000");
        assertThat(r.total()).isEqualByComparingTo("360000");
        assertThat(sumLines(r)).isEqualByComparingTo(r.total());
    }

    @Test void baseAndDistanceOnlyWithIncludedKilometres() {
        var included = new PricingRates(null, "x", "T", d("150000"), d("10000"), d("5"), d("0"), d("0"), d("0"), d("0"), d("0"), d("0"), "VND", List.of(), List.of(), List.of());
        PricingResult r = PricingCalculator.calculate(included, input("12.5", "100", "1", 2, List.of()));
        assertThat(r.component(PriceComponent.BASE)).isEqualByComparingTo("150000");
        assertThat(r.component(PriceComponent.DISTANCE)).isEqualByComparingTo("75000"); // (12.5 - 5) × 10,000
        assertThat(r.total()).isEqualByComparingTo("225000");
        PricingResult short_ = PricingCalculator.calculate(included, input("3", "100", "1", 2, List.of()));
        assertThat(short_.component(PriceComponent.DISTANCE)).isZero();
    }

    @Test void weightAndVolumeSurchargesUseIncludedThresholdsPerVehicle() {
        PricingResult r = PricingCalculator.calculate(rates(), new PricingInput(d("10"), 2, d("1300"), d("12.5"), 10, 2, null, List.of(), null));
        assertThat(r.component(PriceComponent.BASE)).isEqualByComparingTo("200000");
        assertThat(r.component(PriceComponent.DISTANCE)).isEqualByComparingTo("160000"); // 10 km × 2 vehicles × 8,000
        assertThat(r.component(PriceComponent.WEIGHT)).isEqualByComparingTo("150000");  // (1300 − 2×500) × 500
        assertThat(r.component(PriceComponent.VOLUME)).isEqualByComparingTo("100000");  // (12.5 − 2×5) × 40,000
    }

    @Test void serviceFeeTypes() {
        var fees = List.of(
                new PricingRates.ServiceFee("LOADING_SUPPORT", "TEAM", FeeType.FIXED, d("200000"), d("0")),
                new PricingRates.ServiceFee("LOADING_SUPPORT", null, FeeType.FIXED, d("50000"), d("0")),
                new PricingRates.ServiceFee("FRAGILE_GOODS", null, FeeType.PERCENTAGE, d("0"), d("10")),
                new PricingRates.ServiceFee("UPSTAIRS_DELIVERY", null, FeeType.PER_UNIT, d("15000"), d("0")),
                new PricingRates.ServiceFee("PROOF_OF_DELIVERY_PHOTO", null, FeeType.PER_STOP, d("5000"), d("0")),
                new PricingRates.ServiceFee("EASY_PARKING", null, FeeType.FREE, d("0"), d("0")));
        PricingResult r = PricingCalculator.calculate(rates(fees, List.of(), List.of(), "0"), input("10", "100", "1", 3, List.of(
                new SelectedService("LOADING_SUPPORT", "Bốc hàng", false, "TEAM"),
                new SelectedService("FRAGILE_GOODS", "Hàng dễ vỡ", false, "true"),
                new SelectedService("UPSTAIRS_DELIVERY", "Lên tầng", true, "4"),
                new SelectedService("PROOF_OF_DELIVERY_PHOTO", "Ảnh xác nhận", false, "true"),
                new SelectedService("EASY_PARKING", "Đỗ xe", false, "true"),
                new SelectedService("COVER_REQUIRED", "Che chắn", false, "true"))));
        assertThat(r.lines()).filteredOn(l -> l.component() == PriceComponent.SERVICE).extracting(l -> l.code() + "=" + l.amount().toPlainString())
                .containsExactly("SERVICE:LOADING_SUPPORT=200000", // option-specific fee wins
                        "SERVICE:FRAGILE_GOODS=18000",             // 10% of (100,000 + 80,000)
                        "SERVICE:UPSTAIRS_DELIVERY=60000",         // 4 floors × 15,000
                        "SERVICE:PROOF_OF_DELIVERY_PHOTO=15000",   // 3 stops × 5,000
                        "SERVICE:EASY_PARKING=0", "SERVICE:COVER_REQUIRED=0");
        assertThat(r.component(PriceComponent.SERVICE)).isEqualByComparingTo("293000");
    }

    @Test void additionalStopsBeyondFirstPickupAndDelivery() {
        assertThat(PricingCalculator.calculate(rates(), input("10", "0", "0", 2, List.of())).component(PriceComponent.STOP)).isZero();
        assertThat(PricingCalculator.calculate(rates(), input("10", "0", "0", 5, List.of())).component(PriceComponent.STOP)).isEqualByComparingTo("60000");
    }

    @Test void timeWindowsIncludingOvernightAndWeekdays() {
        var night = new PricingRates.TimeRule("Ban đêm", Set.of(), LocalTime.of(22, 0), LocalTime.of(6, 0), FeeType.PERCENTAGE, d("0"), d("20"));
        var weekend = new PricingRates.TimeRule("Cuối tuần", Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), LocalTime.of(0, 0), LocalTime.of(23, 59), FeeType.FIXED, d("50000"), d("0"));
        PricingRates r = rates(List.of(), List.of(night, weekend), List.of(), "0");
        PricingInput at = input("10", "0", "0", 2, List.of());
        java.util.function.Function<String, BigDecimal> time = when -> PricingCalculator.calculate(r,
                new PricingInput(at.distanceKm(), 1, at.totalWeightKg(), at.totalVolumeM3(), 1, 2, LocalDateTime.parse(when), List.of(), null)).component(PriceComponent.TIME);
        assertThat(time.apply("2026-11-04T10:00")).isZero();                        // Wednesday daytime
        assertThat(time.apply("2026-11-04T23:30")).isEqualByComparingTo("36000");   // 20% of 180,000
        assertThat(time.apply("2026-11-05T05:59")).isEqualByComparingTo("36000");   // after midnight, same window
        assertThat(time.apply("2026-11-05T06:00")).isZero();
        assertThat(time.apply("2026-11-07T10:00")).isEqualByComparingTo("50000");   // Saturday
        var fridayNight = new PricingRates.TimeRule("Đêm thứ Sáu", Set.of(DayOfWeek.FRIDAY), LocalTime.of(22, 0), LocalTime.of(2, 0), FeeType.FIXED, d("10000"), d("0"));
        PricingRates fr = rates(List.of(), List.of(fridayNight), List.of(), "0");
        assertThat(PricingCalculator.calculate(fr, new PricingInput(d("10"), 1, d("0"), d("0"), 1, 2, LocalDateTime.parse("2026-11-07T01:00"), List.of(), null))
                .component(PriceComponent.TIME)).as("Saturday 01:00 belongs to Friday night").isEqualByComparingTo("10000");
    }

    @Test void extraFeesMinimumPriceAndRounding() {
        var fuel = new PricingRates.ExtraFee("FUEL", "Phụ phí nhiên liệu", FeeType.PERCENTAGE, d("0"), d("3.333"));
        var platform = new PricingRates.ExtraFee("PLATFORM", "Phí nền tảng", FeeType.FIXED, d("9999.5"), d("0"));
        PricingResult r = PricingCalculator.calculate(rates(List.of(), List.of(), List.of(fuel, platform), "0"), input("1.37", "0", "0", 2, List.of()));
        // distance 1.37 × 8000 = 10,960; transport 110,960; fuel 3.333% = 3,698.30 → 3,698; platform 9,999.5 → 10,000
        assertThat(r.component(PriceComponent.DISTANCE)).isEqualByComparingTo("10960");
        assertThat(r.component(PriceComponent.OTHER)).isEqualByComparingTo("13698");
        assertThat(r.lines()).allSatisfy(l -> assertThat(l.amount().scale()).isLessThanOrEqualTo(0));
        assertThat(r.total()).isEqualByComparingTo(sumLines(r)).isEqualByComparingTo("124658");

        PricingResult minimum = PricingCalculator.calculate(rates(List.of(), List.of(), List.of(), "300000"), input("1", "0", "0", 2, List.of()));
        assertThat(minimum.total()).isEqualByComparingTo("300000");
        assertThat(minimum.lines()).extracting(QuoteLine::code).contains("MINIMUM_PRICE");
    }

    @Test void manualAdjustmentMayLowerButNeverBelowZero() {
        PricingResult r = PricingCalculator.calculate(rates(), new PricingInput(d("10"), 1, d("0"), d("0"), 1, 2, null, List.of(), d("-30000")));
        assertThat(r.total()).isEqualByComparingTo("150000");
        assertThatThrownBy(() -> PricingCalculator.calculate(rates(), new PricingInput(d("10"), 1, d("0"), d("0"), 1, 2, null, List.of(), d("-999999"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test void rejectsInvalidInput() {
        assertThatThrownBy(() -> PricingCalculator.calculate(rates(), input("0", "0", "0", 2, List.of()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PricingCalculator.calculate(rates(), input("10", "-1", "0", 2, List.of()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PricingCalculator.calculate(rates(), input("10", "0", "0", 1, List.of()))).isInstanceOf(BusinessException.class);
    }

    @Test void descriptionsUseVietnameseNumberFormat() {
        PricingResult r = PricingCalculator.calculate(rates(List.of(), List.of(), List.of(new PricingRates.ExtraFee("FUEL", "Nhiên liệu", FeeType.PERCENTAGE, d("0"), d("3.5"))), "1500000"),
                input("18.5", "0", "0", 2, List.of()));
        assertThat(r.lines()).extracting(QuoteLine::description).contains("18,5 km", "3,5% cước vận chuyển", "Giá tối thiểu 1.500.000 VNĐ");
    }
}
