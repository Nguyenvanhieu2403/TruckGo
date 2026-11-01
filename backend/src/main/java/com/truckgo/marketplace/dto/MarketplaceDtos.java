package com.truckgo.marketplace.dto;

import com.truckgo.marketplace.ApplicationStatus;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.shipment.StopType;
import com.truckgo.shipment.dto.ShipmentResponses.ItemResponse;
import com.truckgo.shipment.dto.ShipmentResponses.ServiceResponse;
import com.truckgo.shipment.dto.ShipmentResponses.VehicleRequirement;
import com.truckgo.vehicle.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MarketplaceDtos {
    private MarketplaceDtos() { }

    // ------------------------------------------------------------------ requests

    public record ApplyRequest(@NotNull(message = "Vui lòng chọn xe") UUID vehicleId,
                               @Size(max = 500, message = "Lời nhắn tối đa 500 ký tự") String driverMessage) { }

    public record ReasonRequest(@Size(max = 500, message = "Lý do tối đa 500 ký tự") String reason) { }

    /** Removing a driver always needs a reason (it is shown to the driver and kept in the history). */
    public record ReleaseRequest(@NotBlank(message = "Vui lòng nhập lý do") @Size(max = 500, message = "Lý do tối đa 500 ký tự") String reason) { }

    public record ReplaceRequest(@NotNull(message = "Vui lòng chọn tài xế thay thế") UUID applicationId,
                                 @NotBlank(message = "Vui lòng nhập lý do") @Size(max = 500, message = "Lý do tối đa 500 ký tự") String reason) { }

    // ------------------------------------------------------------------ driver: marketplace

    /** Area only (province/district/ward): exact address and contact are revealed to the assigned driver. */
    public record Area(String province, String district, String ward) { }

    /** Listing row; shared by every driver, so it carries no per-driver data and can be cached. */
    public record MarketplaceShipmentSummary(UUID id, String shipmentCode, Instant pickupTime, Instant deliveryDeadline, Instant applicationDeadline,
                                             Area pickup, Area delivery, int deliveryStopCount, String vehicleTypeCode, String vehicleTypeName,
                                             String bodyTypeCode, int totalQuantity, BigDecimal totalWeightKg, BigDecimal totalVolumeM3,
                                             BigDecimal price, BigDecimal distanceKm, Instant publishedAt, int vehicleCount, int assignedVehicleCount) { }

    /** Listing row plus the viewing driver's own application state (never cached). */
    public record MarketplaceListItem(MarketplaceShipmentSummary shipment, ApplicationStatus myApplicationStatus) { }

    public record StopView(StopType stopType, int sequenceNo, String province, String district, String ward,
                           String fullAddress, String contactName, String contactPhone, String note) { }

    /** reasons include schedule conflicts of this vehicle; eligible = no reason at all. */
    public record VehicleOption(UUID id, String plateNumber, VehicleType vehicleType, String bodyTypeCode, Integer maxPayloadKg, Integer lengthCm, Integer widthCm,
                                Integer heightCm, boolean eligible, List<String> reasons) { }

    public record MyApplicationBrief(UUID id, ApplicationStatus status, UUID vehicleId, String plateNumber, String driverMessage, String responseReason,
                                     Instant appliedAt, Instant respondedAt) { }

    public record MarketplaceShipmentDetail(UUID id, String shipmentCode, ShipmentStatus status, Instant pickupTime, Instant deliveryDeadline,
                                            Instant applicationDeadline, VehicleRequirement vehicle, int totalQuantity, BigDecimal totalWeightKg,
                                            BigDecimal totalVolumeM3, String customerNote, BigDecimal price, BigDecimal distanceKm, boolean assignedToMe,
                                            List<StopView> stops, List<ItemResponse> items, List<ServiceResponse> services,
                                            boolean driverApproved, boolean canApply, String unavailableReason, MyApplicationBrief myApplication,
                                            List<VehicleOption> vehicles, int vehicleCount, int assignedVehicleCount, List<String> scheduleConflicts) { }

    // ------------------------------------------------------------------ driver: my applications

    public record ShipmentBrief(UUID id, String shipmentCode, ShipmentStatus status, Instant pickupTime, Area pickup, Area delivery,
                                BigDecimal totalWeightKg, BigDecimal price, BigDecimal distanceKm) { }

    public record VehicleBrief(UUID id, String plateNumber, VehicleType vehicleType, String bodyTypeCode, Integer maxPayloadKg, Integer lengthCm, Integer widthCm,
                               Integer heightCm) { }

    public record DriverApplicationResponse(UUID id, ApplicationStatus status, ShipmentBrief shipment, VehicleBrief vehicle, String driverMessage,
                                            String responseReason, Instant appliedAt, Instant respondedAt, boolean withdrawable,
                                            List<StopView> stops) { }

    // ------------------------------------------------------------------ customer: applicants

    /** No avatar or rating: those do not exist yet. completedTrips is counted from real orders. */
    public record DriverBrief(UUID driverId, String fullName, String licenseClass, long completedTrips, String phone) { }

    public record ApplicantResponse(UUID id, ApplicationStatus status, DriverBrief driver, VehicleBrief vehicle, String driverMessage, String responseReason,
                                    Instant appliedAt, Instant respondedAt, boolean vehicleEligible, List<String> vehicleIssues) { }

    // ------------------------------------------------------------------ admin

    public record AdminMarketplaceShipment(UUID id, String shipmentCode, ShipmentStatus status, Instant pickupTime, Area pickup, Area delivery,
                                           BigDecimal price, int vehicleCount, long pendingApplications, long totalApplications,
                                           UUID assignedDriverId, String assignedDriverName, Instant assignedAt, Instant publishedAt, int assignedVehicleCount) { }

    public record AdminApplicationResponse(UUID id, ApplicationStatus status, UUID shipmentId, String shipmentCode, ShipmentStatus shipmentStatus,
                                           UUID driverId, String driverName, String driverPhone, UUID vehicleId, String plateNumber,
                                           String driverMessage, String responseReason, Instant appliedAt, Instant respondedAt) { }

    public record AdminAssignmentResponse(UUID id, UUID shipmentId, String shipmentCode, String action, UUID newDriverId, String newDriverName,
                                          UUID previousDriverId, UUID vehicleId, String plateNumber, UUID applicationId, String reason,
                                          UUID performedBy, String performedByName, Instant createdAt, String previousDriverName, String actorType,
                                          String systemActor, UUID assignmentId) { }

    // ------------------------------------------------------------------ assignments (customer and admin)

    /** One vehicle slot of an order. The driver's phone is shown while the assignment is active. */
    public record AssignmentView(UUID id, int slotNo, String status, boolean lead, DriverBrief driver, VehicleBrief vehicle, UUID applicationId,
                                 Instant busyFrom, Instant busyUntil, Instant assignedAt, Instant releasedAt, String releaseReason, Instant finishedAt) { }

    /** canChange: drivers can still be removed or replaced (before the trip starts). */
    public record AssignmentOverview(UUID shipmentId, String shipmentCode, ShipmentStatus status, int vehicleCount, int assignedCount, int remaining,
                                     boolean canChange, String changeBlockedReason, List<AssignmentView> assignments, List<AdminAssignmentResponse> history) { }

    /** One busy period of a driver or vehicle (admin schedule view). */
    public record ScheduleEntry(UUID assignmentId, UUID shipmentId, String shipmentCode, ShipmentStatus shipmentStatus, UUID driverId, String driverName,
                                UUID vehicleId, String plateNumber, Instant busyFrom, Instant busyUntil, boolean lead) { }
}
