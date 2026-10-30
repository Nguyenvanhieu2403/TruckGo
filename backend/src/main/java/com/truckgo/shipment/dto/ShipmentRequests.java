package com.truckgo.shipment.dto;

import com.truckgo.shipment.StopType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Request bodies. There is no customerId, status, total or price field: ownership comes from the token, totals are
 * computed by the server and unknown JSON properties are ignored.
 */
public final class ShipmentRequests {
    private ShipmentRequests() { }

    /** Used for POST /shipments/draft and PUT /shipments/{id}/draft (where {@code version} is required). */
    public record DraftRequest(
            OffsetDateTime pickupTime,
            OffsetDateTime deliveryDeadline,
            @Size(max = 40) String vehicleTypeCode,
            @Size(max = 40) String bodyTypeCode,
            @Min(value = 1, message = "Số xe tối thiểu là 1") @Max(value = 20, message = "Số xe tối đa là 20") Integer vehicleCount,
            @Positive(message = "Tải trọng phải lớn hơn 0") @Digits(integer = 8, fraction = 2, message = "Tải trọng không hợp lệ") BigDecimal minimumPayloadKg,
            @Positive(message = "Chiều dài phải lớn hơn 0") @Digits(integer = 6, fraction = 2, message = "Kích thước không hợp lệ") BigDecimal minCargoLengthCm,
            @Positive(message = "Chiều rộng phải lớn hơn 0") @Digits(integer = 6, fraction = 2, message = "Kích thước không hợp lệ") BigDecimal minCargoWidthCm,
            @Positive(message = "Chiều cao phải lớn hơn 0") @Digits(integer = 6, fraction = 2, message = "Kích thước không hợp lệ") BigDecimal minCargoHeightCm,
            @Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự") String customerNote,
            @NotNull(message = "Cần ít nhất một điểm lấy hàng và một điểm giao hàng") @Size(min = 2, max = 10, message = "Đơn cần 2–10 điểm dừng") List<@Valid @NotNull StopRequest> stops,
            @NotNull(message = "Cần ít nhất một loại hàng hóa") @Size(min = 1, max = 50, message = "Đơn cần 1–50 dòng hàng hóa") List<@Valid @NotNull ItemRequest> items,
            @Size(max = 30, message = "Quá nhiều dịch vụ") List<@Valid @NotNull ServiceRequest> services,
            Long version) { }

    public record StopRequest(
            @NotNull(message = "Chọn loại điểm dừng") StopType stopType,
            Integer sequenceNo,
            @NotBlank(message = "Nhập tên người liên hệ") @Size(max = 100, message = "Tên tối đa 100 ký tự") String contactName,
            @NotBlank(message = "Nhập số điện thoại liên hệ") @Size(max = 20, message = "Số điện thoại quá dài") String contactPhone,
            @NotBlank(message = "Nhập tỉnh/thành phố") @Size(max = 100) String province,
            @Size(max = 100) String district,
            @Size(max = 100) String ward,
            @NotBlank(message = "Nhập địa chỉ chi tiết") @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự") String addressDetail,
            @DecimalMin(value = "-90", message = "Vĩ độ từ -90 đến 90") @DecimalMax(value = "90", message = "Vĩ độ từ -90 đến 90") @Digits(integer = 3, fraction = 6) BigDecimal latitude,
            @DecimalMin(value = "-180", message = "Kinh độ từ -180 đến 180") @DecimalMax(value = "180", message = "Kinh độ từ -180 đến 180") @Digits(integer = 3, fraction = 6) BigDecimal longitude,
            @Size(max = 500, message = "Ghi chú tối đa 500 ký tự") String note) { }

    public record ItemRequest(
            @NotBlank(message = "Nhập tên hàng") @Size(max = 150, message = "Tên hàng tối đa 150 ký tự") String itemName,
            @NotBlank(message = "Chọn loại hàng") @Size(max = 40) String category,
            @Size(max = 500, message = "Mô tả tối đa 500 ký tự") String description,
            @NotNull(message = "Nhập số kiện") @Min(value = 1, message = "Số kiện phải lớn hơn 0") @Max(value = 100000, message = "Số kiện quá lớn") Integer quantity,
            @NotNull(message = "Nhập trọng lượng mỗi kiện") @Positive(message = "Trọng lượng phải lớn hơn 0") @Digits(integer = 8, fraction = 2, message = "Trọng lượng tối đa 2 chữ số thập phân") BigDecimal weightPerUnitKg,
            @Positive(message = "Chiều dài phải lớn hơn 0") @Digits(integer = 6, fraction = 2, message = "Kích thước không hợp lệ") BigDecimal lengthCm,
            @Positive(message = "Chiều rộng phải lớn hơn 0") @Digits(integer = 6, fraction = 2, message = "Kích thước không hợp lệ") BigDecimal widthCm,
            @Positive(message = "Chiều cao phải lớn hơn 0") @Digits(integer = 6, fraction = 2, message = "Kích thước không hợp lệ") BigDecimal heightCm) { }

    public record ServiceRequest(
            @NotBlank(message = "Thiếu mã dịch vụ") @Size(max = 40) String serviceCode,
            @Size(max = 500, message = "Giá trị tối đa 500 ký tự") String serviceValue) { }

    public record CancelRequest(
            @NotBlank(message = "Vui lòng nhập lý do hủy") @Size(min = 5, max = 500, message = "Lý do dài 5–500 ký tự") String reason) { }
}
