package com.truckgo.shipment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CargoCalculatorTest {
    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test void itemWeightIsQuantityTimesUnitWeight() {
        assertThat(CargoCalculator.itemWeight(10, d("20"))).isEqualByComparingTo("200.00");
        assertThat(CargoCalculator.itemWeight(3, d("0.1"))).isEqualTo(d("0.30"));
    }

    @Test void volumeIsLengthWidthHeightOverOneMillion() {
        assertThat(CargoCalculator.itemVolume(10, d("50"), d("40"), d("30"))).isEqualTo(d("0.600000"));
        assertThat(CargoCalculator.itemVolume(1, d("70"), d("70"), d("180"))).isEqualTo(d("0.882000"));
        assertThat(CargoCalculator.itemVolume(1, d("0.5"), d("0.5"), d("0.5"))).isEqualTo(d("0.000000"));
        assertThat(CargoCalculator.itemVolume(1, null, d("70"), d("180"))).isNull();
    }

    @Test void decimalArithmeticHasNoFloatingPointDrift() {
        // 3 × 0.1 kg would be 0.30000000000000004 with double.
        assertThat(CargoCalculator.itemWeight(3, d("0.1")).toPlainString()).isEqualTo("0.30");
        assertThat(CargoCalculator.itemVolume(3, d("10.1"), d("10.1"), d("10.1"))).isEqualTo(d("0.003091"));
    }

    @Test void shipmentTotalsFollowTheSpecExample() {
        Shipment shipment = new Shipment("TG000000-AAAAA", UUID.randomUUID());
        Instant now = Instant.now();
        shipment.addItem(new ShipmentItem("Thùng hàng A", "CONSUMER_GOODS", null, 10, d("20"), d("50"), d("40"), d("30"), now));
        shipment.addItem(new ShipmentItem("Bàn làm việc", "FURNITURE", null, 2, d("35"), d("120"), d("60"), d("75"), now));
        shipment.addItem(new ShipmentItem("Tủ lạnh", "ELECTRONICS", null, 1, d("60"), d("70"), d("70"), d("180"), now));
        shipment.recalculateTotals();
        assertThat(shipment.getTotalQuantity()).isEqualTo(13);
        assertThat(shipment.getTotalWeightKg()).isEqualTo(d("330.00"));
        assertThat(shipment.getTotalVolumeM3()).isEqualTo(d("2.562000"));
    }

    @Test void itemsWithoutDimensionsDoNotAddVolume() {
        Shipment shipment = new Shipment("TG000000-AAAAA", UUID.randomUUID());
        shipment.addItem(new ShipmentItem("Bao gạo", "AGRICULTURAL", null, 5, d("50"), null, null, null, Instant.now()));
        shipment.recalculateTotals();
        assertThat(shipment.getTotalWeightKg()).isEqualTo(d("250.00"));
        assertThat(shipment.getTotalVolumeM3()).isEqualTo(d("0.000000"));
        assertThat(shipment.getItems().get(0).hasDimensions()).isFalse();
    }
}
