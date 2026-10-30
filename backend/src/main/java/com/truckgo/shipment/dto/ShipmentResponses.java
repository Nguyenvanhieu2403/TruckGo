package com.truckgo.shipment.dto;

import com.truckgo.catalog.ServiceValueType;
import com.truckgo.shipment.AttachmentFileType;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.shipment.StopType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ShipmentResponses {
    private ShipmentResponses() { }

    /** Prices are not computed in Sprint 3; clients show "not quoted yet" instead of a number. */
    public enum PriceStatus { NOT_QUOTED, QUOTED, FINAL }

    public record Warning(String code, String message) { }

    public record Actions(boolean editable, boolean publishable, boolean cancellable, boolean deletable, boolean attachmentsEditable) { }

    public record StopResponse(UUID id, StopType stopType, int sequenceNo, String contactName, String contactPhone, String province, String district,
                               String ward, String addressDetail, String fullAddress, BigDecimal latitude, BigDecimal longitude, String note) { }

    public record ItemResponse(UUID id, String itemName, String category, String categoryName, String description, int quantity,
                               BigDecimal weightPerUnitKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                               BigDecimal totalWeightKg, BigDecimal totalVolumeM3) { }

    public record ServiceResponse(String serviceCode, String serviceName, String group, ServiceValueType valueType, String serviceValue, String valueLabel) { }

    public record AttachmentResponse(UUID id, String fileName, String contentType, long fileSize, AttachmentFileType fileType, Instant createdAt, String contentUrl) { }

    public record StatusHistoryResponse(ShipmentStatus oldStatus, ShipmentStatus newStatus, UUID changedBy, String changeReason, Instant createdAt) { }

    public record VehicleRequirement(String vehicleTypeCode, String vehicleTypeName, BigDecimal vehicleTypeMaxPayloadKg, String bodyTypeCode, String bodyTypeName,
                                     int vehicleCount, BigDecimal minimumPayloadKg, BigDecimal minCargoLengthCm, BigDecimal minCargoWidthCm,
                                     BigDecimal minCargoHeightCm) { }

    public record ShipmentDetail(UUID id, String shipmentCode, ShipmentStatus status, UUID customerId, Instant pickupTime, Instant deliveryDeadline,
                                 VehicleRequirement vehicle, int totalQuantity, BigDecimal totalWeightKg, BigDecimal totalVolumeM3, String customerNote,
                                 PriceStatus priceStatus, BigDecimal estimatedPrice, BigDecimal finalPrice,
                                 StopResponse pickup, StopResponse delivery, List<StopResponse> stops, List<ItemResponse> items,
                                 List<ServiceResponse> services, List<AttachmentResponse> attachments, List<StatusHistoryResponse> statusHistory,
                                 List<Warning> warnings, Actions actions, Instant publishedAt, Instant cancelledAt, String cancellationReason,
                                 Instant createdAt, Instant updatedAt, long version) { }

    public record ShipmentSummary(UUID id, String shipmentCode, ShipmentStatus status, Instant createdAt, Instant pickupTime,
                                  String pickupAddress, String deliveryAddress, int deliveryStopCount, int totalQuantity, BigDecimal totalWeightKg,
                                  BigDecimal totalVolumeM3, String vehicleTypeCode, String vehicleTypeName, Actions actions) { }
}
