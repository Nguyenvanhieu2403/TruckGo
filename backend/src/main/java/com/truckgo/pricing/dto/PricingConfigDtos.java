package com.truckgo.pricing.dto;

import com.truckgo.pricing.engine.FeeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Admin price-table payloads. Amounts are VND; percentages are 0–1000. */
public final class PricingConfigDtos {
    private PricingConfigDtos() { }

    private static final String MONEY = "Số tiền không hợp lệ (tối đa 12 chữ số, 2 số lẻ)";

    public record ConfigRequest(
            @NotBlank(message = "Nhập tên bảng giá") @Size(max = 150) String name,
            @NotBlank(message = "Chọn loại xe") @Size(max = 40) String vehicleTypeCode,
            @NotNull(message = "Nhập giá mở cửa") @PositiveOrZero(message = "Không được âm") @Digits(integer = 12, fraction = 2, message = MONEY) BigDecimal basePrice,
            @NotNull(message = "Nhập giá mỗi km") @PositiveOrZero(message = "Không được âm") @Digits(integer = 12, fraction = 2, message = MONEY) BigDecimal pricePerKm,
            @NotNull @PositiveOrZero(message = "Không được âm") @Digits(integer = 8, fraction = 2) BigDecimal includedDistanceKm,
            @NotNull @PositiveOrZero(message = "Không được âm") @Digits(integer = 10, fraction = 2) BigDecimal includedWeightKg,
            @NotNull @PositiveOrZero(message = "Không được âm") @Digits(integer = 12, fraction = 2, message = MONEY) BigDecimal pricePerExtraKg,
            @NotNull @PositiveOrZero(message = "Không được âm") @Digits(integer = 8, fraction = 6) BigDecimal includedVolumeM3,
            @NotNull @PositiveOrZero(message = "Không được âm") @Digits(integer = 12, fraction = 2, message = MONEY) BigDecimal pricePerExtraM3,
            @NotNull @PositiveOrZero(message = "Không được âm") @Digits(integer = 12, fraction = 2, message = MONEY) BigDecimal additionalStopPrice,
            @NotNull @PositiveOrZero(message = "Không được âm") @Digits(integer = 12, fraction = 2, message = MONEY) BigDecimal minimumPrice,
            @NotNull(message = "Chọn thời điểm bắt đầu hiệu lực") OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            @Size(max = 20) List<@Valid @NotNull TimeRuleRequest> timeRules,
            @Size(max = 20) List<@Valid @NotNull ExtraFeeRequest> extraFees,
            Long version) { }

    public record TimeRuleRequest(
            @NotBlank(message = "Nhập tên khung giờ") @Size(max = 100) String name,
            @Pattern(regexp = "^((MON|TUE|WED|THU|FRI|SAT|SUN)(,(MON|TUE|WED|THU|FRI|SAT|SUN))*)?$", message = "Ngày trong tuần dạng MON,TUE,…") String daysOfWeek,
            @NotNull(message = "Nhập giờ bắt đầu") LocalTime startTime,
            @NotNull(message = "Nhập giờ kết thúc") LocalTime endTime,
            @NotNull FeeType feeType,
            @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @PositiveOrZero @DecimalMax("1000") @Digits(integer = 4, fraction = 4) BigDecimal percentage) { }

    public record ExtraFeeRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_]{2,40}$", message = "Mã gồm chữ in hoa, số, gạch dưới") String code,
            @NotBlank(message = "Nhập tên phí") @Size(max = 100) String name,
            @NotNull FeeType feeType,
            @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @PositiveOrZero @DecimalMax("1000") @Digits(integer = 4, fraction = 4) BigDecimal percentage) { }

    public record ServiceFeeRequest(
            @NotNull(message = "Chọn bảng giá") UUID pricingConfigId,
            @NotBlank(message = "Chọn dịch vụ") @Size(max = 40) String serviceCode,
            @Size(max = 40) String serviceOptionValue,
            @NotNull(message = "Chọn cách tính") FeeType pricingType,
            @PositiveOrZero(message = "Không được âm") @Digits(integer = 12, fraction = 2, message = MONEY) BigDecimal amount,
            @PositiveOrZero(message = "Không được âm") @DecimalMax(value = "1000", message = "Tối đa 1000%") @Digits(integer = 4, fraction = 4) BigDecimal percentage,
            Long version) { }

    public record TimeRuleResponse(String name, String daysOfWeek, LocalTime startTime, LocalTime endTime, FeeType feeType, BigDecimal amount, BigDecimal percentage) { }

    public record ExtraFeeResponse(String code, String name, FeeType feeType, BigDecimal amount, BigDecimal percentage) { }

    public record ServiceFeeResponse(UUID id, UUID pricingConfigId, String serviceCode, String serviceName, String serviceOptionValue, String serviceOptionLabel,
                                     FeeType pricingType, BigDecimal amount, BigDecimal percentage, boolean active, long version, Instant updatedAt) { }

    /** effectiveNow: active and inside its period at the time of the request. */
    public record ConfigResponse(UUID id, String name, String vehicleTypeCode, String vehicleTypeName, BigDecimal basePrice, BigDecimal pricePerKm,
                                 BigDecimal includedDistanceKm, BigDecimal includedWeightKg, BigDecimal pricePerExtraKg, BigDecimal includedVolumeM3,
                                 BigDecimal pricePerExtraM3, BigDecimal additionalStopPrice, BigDecimal minimumPrice, String currency, Instant effectiveFrom,
                                 Instant effectiveTo, boolean active, boolean effectiveNow, List<TimeRuleResponse> timeRules, List<ExtraFeeResponse> extraFees,
                                 List<ServiceFeeResponse> serviceFees, Instant createdAt, Instant updatedAt, long version) { }

    public record HistoryResponse(UUID id, String action, String oldValue, String newValue, UUID changedBy, Instant changedAt) { }
}
