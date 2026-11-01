package com.truckgo.pricing;

import com.truckgo.pricing.engine.FeeType;
import com.truckgo.pricing.engine.PricingCalculator;
import com.truckgo.pricing.engine.PricingInput;
import com.truckgo.pricing.engine.PricingInput.StopService;
import com.truckgo.pricing.engine.PricingRates;
import com.truckgo.pricing.engine.PricingResult;
import com.truckgo.pricing.engine.QuoteLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.truckgo.pricing.PricingCalculatorTest.rates;
import static org.assertj.core.api.Assertions.assertThat;

class StopServicePricingTest {
    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static PricingResult price(List<PricingRates.ServiceFee> fees, List<PricingInput.SelectedService> orderWide, List<StopService> perStop, int stops) {
        return PricingCalculator.calculate(rates(fees, List.of(), List.of(), "0"),
                new PricingInput(d("20"), 1, d("600"), d("3"), 10, stops, null, orderWide, null, perStop));
    }

    private static QuoteLine line(PricingResult r, String code) { return r.lines().stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow(); }

    private static final List<PricingRates.ServiceFee> FEES = List.of(
            new PricingRates.ServiceFee("LOADING_SUPPORT", "TWO_PEOPLE", FeeType.FIXED, d("110000"), d("0")),
            new PricingRates.ServiceFee("UNLOADING_SUPPORT", "TWO_PEOPLE", FeeType.FIXED, d("90000"), d("0")),
            new PricingRates.ServiceFee("UNLOADING_SUPPORT", "TEAM", FeeType.PERCENTAGE, d("0"), d("10")));

    @Test void loadingAtThePickupAndUnloadingAtEachDeliveryAreChargedOncePerStop() {
        PricingResult r = price(FEES, List.of(), List.of(
                new StopService(1, "PICKUP", "LOADING_SUPPORT", "Hỗ trợ bốc hàng", false, "TWO_PEOPLE"),
                new StopService(2, "DELIVERY", "UNLOADING_SUPPORT", "Hỗ trợ dỡ hàng", false, "TWO_PEOPLE"),
                new StopService(3, "DELIVERY", "UNLOADING_SUPPORT", "Hỗ trợ dỡ hàng", false, "TWO_PEOPLE")), 3);
        QuoteLine load = line(r, "STOP_SERVICE:LOADING_SUPPORT:TWO_PEOPLE");
        QuoteLine unload = line(r, "STOP_SERVICE:UNLOADING_SUPPORT:TWO_PEOPLE");
        assertThat(load.amount()).isEqualByComparingTo("110000");
        assertThat(load.stopSequences()).containsExactly(1);
        assertThat(unload.quantity()).isEqualByComparingTo("2");
        assertThat(unload.amount()).isEqualByComparingTo("180000");
        assertThat(unload.appliesTo()).isEqualTo("STOP");
        assertThat(unload.description()).contains("Tại điểm 2, 3");
        assertThat(r.lines().stream().map(QuoteLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(r.total());
    }

    @Test void differentOptionsAtDifferentStopsArePricedSeparately() {
        PricingResult r = price(FEES, List.of(), List.of(
                new StopService(2, "DELIVERY", "UNLOADING_SUPPORT", "Hỗ trợ dỡ hàng", false, "TWO_PEOPLE"),
                new StopService(3, "DELIVERY", "UNLOADING_SUPPORT", "Hỗ trợ dỡ hàng", false, "TEAM")), 3);
        assertThat(line(r, "STOP_SERVICE:UNLOADING_SUPPORT:TWO_PEOPLE").amount()).isEqualByComparingTo("90000");
        // 10% of transport (100,000 base + 20 km × 8,000 = 260,000) for one stop, whole đồng.
        assertThat(line(r, "STOP_SERVICE:UNLOADING_SUPPORT:TEAM").amount()).isEqualByComparingTo("26000");
    }

    @Test void orderWideSelectionsKeepTheirOldOneChargeMeaning() {
        PricingResult r = price(FEES, List.of(new PricingInput.SelectedService("UNLOADING_SUPPORT", "Hỗ trợ dỡ hàng", false, "TWO_PEOPLE")), List.of(), 3);
        QuoteLine order = line(r, "SERVICE:UNLOADING_SUPPORT");
        assertThat(order.amount()).isEqualByComparingTo("90000");
        assertThat(order.appliesTo()).isEqualTo("ORDER");
        assertThat(r.lines()).noneMatch(l -> l.code().startsWith("STOP_SERVICE"));
    }

    @Test void unpricedStopServicesAreShownAsFree() {
        PricingResult r = price(List.of(), List.of(), List.of(new StopService(1, "PICKUP", "LOADING_SUPPORT", "Hỗ trợ bốc hàng", false, "TEAM")), 2);
        QuoteLine free = line(r, "STOP_SERVICE:LOADING_SUPPORT:TEAM");
        assertThat(free.amount()).isZero();
        assertThat(free.description()).contains("Miễn phí");
    }
}
