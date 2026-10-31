package com.truckgo.trip.dto;

import com.truckgo.pricing.dto.QuoteDtos.QuoteResponse;
import com.truckgo.shipment.AttachmentFileType;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.shipment.StopType;
import com.truckgo.shipment.dto.ShipmentResponses.ItemResponse;
import com.truckgo.shipment.dto.ShipmentResponses.ServiceResponse;
import com.truckgo.shipment.dto.ShipmentResponses.StatusHistoryResponse;
import com.truckgo.shipment.dto.ShipmentResponses.VehicleRequirement;
import com.truckgo.trip.IncidentStatus;
import com.truckgo.trip.IncidentType;
import com.truckgo.trip.StopStatus;
import com.truckgo.trip.TrackingSession;
import com.truckgo.vehicle.VehicleType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class TripDtos {
    private TripDtos() { }

    // ------------------------------------------------------------------ requests

    /** Coordinates are optional on milestones (the device may refuse location); when given both are required. */
    public record MilestoneRequest(@DecimalMin(value = "-90", message = "Vĩ độ phải trong khoảng -90 đến 90") @DecimalMax(value = "90", message = "Vĩ độ phải trong khoảng -90 đến 90") BigDecimal latitude,
                                   @DecimalMin(value = "-180", message = "Kinh độ phải trong khoảng -180 đến 180") @DecimalMax(value = "180", message = "Kinh độ phải trong khoảng -180 đến 180") BigDecimal longitude,
                                   @Size(max = 500, message = "Ghi chú tối đa 500 ký tự") String note,
                                   @Size(max = 10, message = "Tối đa 10 ảnh") List<UUID> attachments) {
        public List<UUID> attachmentIds() { return attachments == null ? List.of() : attachments; }
    }

    /**
     * speed in km/h, heading in degrees clockwise from north, accuracy in metres. ageMs (optional, preferred) is how
     * old the fix is by the device's own clock; the server then dates the fix by its clock, so a device whose clock is
     * set wrong still reports correctly. recordedAt is used when ageMs is absent.
     */
    public record LocationRequest(@NotNull(message = "Thiếu vĩ độ") @DecimalMin(value = "-90", message = "Vĩ độ phải trong khoảng -90 đến 90") @DecimalMax(value = "90", message = "Vĩ độ phải trong khoảng -90 đến 90") BigDecimal latitude,
                                  @NotNull(message = "Thiếu kinh độ") @DecimalMin(value = "-180", message = "Kinh độ phải trong khoảng -180 đến 180") @DecimalMax(value = "180", message = "Kinh độ phải trong khoảng -180 đến 180") BigDecimal longitude,
                                  @DecimalMin(value = "0", message = "Độ chính xác không được âm") @DecimalMax(value = "100000", message = "Độ chính xác không hợp lệ") BigDecimal accuracy,
                                  @DecimalMin(value = "0", message = "Tốc độ không được âm") @DecimalMax(value = "300", message = "Tốc độ không hợp lệ") BigDecimal speed,
                                  @DecimalMin(value = "0", message = "Hướng phải từ 0 đến dưới 360") @DecimalMax(value = "360", inclusive = false, message = "Hướng phải từ 0 đến dưới 360") BigDecimal heading,
                                  @NotNull(message = "Thiếu thời điểm ghi nhận") OffsetDateTime recordedAt,
                                  @jakarta.validation.constraints.PositiveOrZero(message = "Tuổi của vị trí không hợp lệ") Long ageMs) { }

    public record ConfirmDeliveryRequest(@NotBlank(message = "Nhập mã xác nhận") @Pattern(regexp = "\\d{6}", message = "Mã xác nhận gồm 6 chữ số") String otp,
                                         @Size(max = 500, message = "Ghi chú tối đa 500 ký tự") String note) { }

    public record IncidentRequest(@NotNull(message = "Chọn loại sự cố") IncidentType type,
                                  @NotBlank(message = "Mô tả sự cố") @Size(min = 5, max = 1000, message = "Mô tả dài 5–1000 ký tự") String description,
                                  @DecimalMin(value = "-90", message = "Vĩ độ không hợp lệ") @DecimalMax(value = "90", message = "Vĩ độ không hợp lệ") BigDecimal latitude,
                                  @DecimalMin(value = "-180", message = "Kinh độ không hợp lệ") @DecimalMax(value = "180", message = "Kinh độ không hợp lệ") BigDecimal longitude,
                                  @Size(max = 10, message = "Tối đa 10 ảnh") List<UUID> attachments) {
        public List<UUID> attachmentIds() { return attachments == null ? List.of() : attachments; }
    }

    public record IncidentUpdateRequest(@NotNull(message = "Chọn trạng thái") IncidentStatus status,
                                        @Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự") String note) { }

    // ------------------------------------------------------------------ shared parts

    public record StopProgressView(UUID stopId, StopType stopType, int sequenceNo, String province, String district, String ward, String fullAddress,
                                   String contactName, String contactPhone, String stopNote, BigDecimal latitude, BigDecimal longitude,
                                   StopStatus status, Instant arrivedAt, Instant departedAt, String progressNote, boolean current) { }

    public record PhotoView(UUID id, AttachmentFileType fileType, String fileName, long fileSize, Instant createdAt, String contentUrl) { }

    public record VehicleView(UUID id, String plateNumber, VehicleType vehicleType, Integer maxPayloadKg) { }

    public record Milestones(Instant assignedAt, Instant driverArrivedAt, Instant pickedUpAt, Instant inTransitAt, Instant deliveredAt, Instant completedAt) { }

    /** Which action the assigned driver can take next (null when none). */
    public enum NextAction { ARRIVE_PICKUP, CONFIRM_PICKUP, START_TRIP, ARRIVE_STOP, COMPLETE_STOP, DELIVER, NONE }

    public enum TrackingState { NOT_STARTED, LIVE, STALE, OFFLINE, PAUSED, ENDED }

    /** Coordinates are only present while the trip is in transit; age is measured from the device timestamp. */
    public record TrackingView(TrackingState state, TrackingSession.Status session, BigDecimal latitude, BigDecimal longitude, BigDecimal accuracy,
                               BigDecimal speed, BigDecimal heading, Instant recordedAt, Long ageSeconds) { }

    public record TrackingPoint(BigDecimal latitude, BigDecimal longitude, BigDecimal accuracy, BigDecimal speed, BigDecimal heading, Instant recordedAt) { }

    /** What the driver app should do: send interval while moving/idle and the minimum movement worth sending. */
    public record TrackingPolicy(long movingIntervalSeconds, long idleIntervalSeconds, int minDistanceMeters, long maxAgeSeconds) { }

    public record LocationAck(boolean accepted, String reason, Instant recordedAt, TrackingPolicy policy) { }

    public record ConfirmationView(boolean pending, Instant expiresAt, int attemptsLeft, Instant resendAvailableAt, String channel, boolean locked) { }

    public record IncidentView(UUID id, UUID shipmentId, String shipmentCode, IncidentType type, String description, IncidentStatus status,
                               BigDecimal latitude, BigDecimal longitude, Instant occurredAt, UUID reportedBy, String reportedByName,
                               String resolutionNote, Instant resolvedAt, Instant createdAt, List<PhotoView> photos) { }

    // ------------------------------------------------------------------ driver

    public record TripSummary(UUID id, String shipmentCode, ShipmentStatus status, Instant pickupTime, Instant deliveryDeadline,
                              String pickupAddress, String deliveryAddress, int stopCount, int totalQuantity, BigDecimal totalWeightKg,
                              BigDecimal price, VehicleView vehicle, long openIncidents) { }

    public record DriverTripDetail(UUID id, String shipmentCode, ShipmentStatus status, Instant pickupTime, Instant deliveryDeadline,
                                   String customerName, String customerPhone, VehicleRequirement requirement, VehicleView vehicle,
                                   int totalQuantity, BigDecimal totalWeightKg, BigDecimal totalVolumeM3, String customerNote,
                                   List<StopProgressView> stops, List<ItemResponse> items, List<ServiceResponse> services, QuoteResponse quote,
                                   Milestones milestones, List<StatusHistoryResponse> statusHistory, List<PhotoView> photos,
                                   NextAction nextAction, UUID nextStopId, boolean trackingAllowed, TrackingSession.Status trackingSession,
                                   TrackingPolicy trackingPolicy, boolean deliveryPhotoRequired, boolean awaitingCustomerConfirmation, long openIncidents) { }

    // ------------------------------------------------------------------ customer / admin

    public record DriverView(UUID driverId, String fullName, String phone, String licenseClass) { }

    public record CustomerTripView(UUID id, String shipmentCode, ShipmentStatus status, DriverView driver, VehicleView vehicle,
                                   List<StopProgressView> stops, Milestones milestones, List<StatusHistoryResponse> statusHistory,
                                   List<PhotoView> photos, TrackingView tracking, Instant estimatedArrival, ConfirmationView confirmation,
                                   long openIncidents) { }
}
