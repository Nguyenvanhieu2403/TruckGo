package com.truckgo.pricing.dto;

import com.truckgo.pricing.engine.PriceComponent;
import com.truckgo.pricing.quote.QuoteStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class QuoteDtos {
    private QuoteDtos() { }

    public record RejectRequest(@Size(max = 500, message = "Lý do tối đa 500 ký tự") String reason) { }

    /** At least one of distanceKm / adjustmentAmount; adjustmentAmount may be negative (discount). */
    public record ManualAdjustmentRequest(
            @DecimalMin(value = "0.01", message = "Quãng đường phải lớn hơn 0") @DecimalMax(value = "5000", message = "Quãng đường tối đa 5.000 km")
            @Digits(integer = 4, fraction = 2, message = "Tối đa 2 chữ số thập phân") BigDecimal distanceKm,
            @DecimalMin(value = "-1000000000", message = "Giá trị không hợp lệ") @DecimalMax(value = "1000000000", message = "Giá trị không hợp lệ")
            @Digits(integer = 10, fraction = 0, message = "Số tiền là số nguyên VNĐ") BigDecimal adjustmentAmount,
            @NotBlank(message = "Nhập lý do điều chỉnh") @Size(min = 5, max = 500, message = "Lý do dài 5–500 ký tự") String reason) { }

    public record LineResponse(int lineNo, PriceComponent component, String code, String name, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) { }

    public record Breakdown(BigDecimal basePrice, BigDecimal distancePrice, BigDecimal weightSurcharge, BigDecimal volumeSurcharge, BigDecimal serviceSurcharge,
                            BigDecimal additionalStopSurcharge, BigDecimal timeSurcharge, BigDecimal otherSurcharge) { }

    public record QuoteActions(boolean acceptable, boolean rejectable, boolean recalculable) { }

    /** Customer view; amounts are null unless the quote was priced. */
    public record QuoteResponse(UUID id, String quoteCode, int quoteVersion, QuoteStatus status, UUID shipmentId, String shipmentCode,
                                String vehicleTypeCode, String vehicleTypeName, int vehicleCount, BigDecimal distanceKm, String distanceSource,
                                Integer routeDurationMinutes, BigDecimal totalWeightKg, BigDecimal totalVolumeM3, int stopCount,
                                Breakdown breakdown, BigDecimal totalPrice, String currency, List<LineResponse> lines,
                                String reviewReasonCode, String reviewReason, String rejectionReason, String adjustmentReason,
                                Instant expiresAt, Instant acceptedAt, Instant rejectedAt, Instant supersededAt, Instant createdAt, QuoteActions actions) { }

    /** Admin view adds who adjusted it, the customer and the stored snapshot. */
    public record AdminQuoteResponse(QuoteResponse quote, UUID customerId, String pickupAddress, String deliveryAddress, UUID adjustedBy, UUID pricingConfigId,
                                     String pricingSnapshot) { }
}
