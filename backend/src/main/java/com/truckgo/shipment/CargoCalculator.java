package com.truckgo.shipment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cargo arithmetic with BigDecimal only.
 * <pre>
 *   item weight  = quantity × weightPerUnitKg                      (kg, scale 2)
 *   unit volume  = lengthCm × widthCm × heightCm / 1,000,000       (m³)
 *   item volume  = quantity × unit volume                           (m³, scale 6, HALF_UP)
 *   totals       = Σ items
 * </pre>
 * Volume is unknown (null) when any dimension is missing; such items do not contribute to the total volume.
 */
public final class CargoCalculator {
    public static final int WEIGHT_SCALE = 2;
    public static final int VOLUME_SCALE = 6;
    private static final BigDecimal CM3_PER_M3 = BigDecimal.valueOf(1_000_000);

    private CargoCalculator() { }

    public static BigDecimal itemWeight(int quantity, BigDecimal weightPerUnitKg) {
        return weightPerUnitKg.multiply(BigDecimal.valueOf(quantity)).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal itemVolume(int quantity, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {
        if (lengthCm == null || widthCm == null || heightCm == null) return null;
        return lengthCm.multiply(widthCm).multiply(heightCm).multiply(BigDecimal.valueOf(quantity))
                .divide(CM3_PER_M3, VOLUME_SCALE, RoundingMode.HALF_UP);
    }
}
