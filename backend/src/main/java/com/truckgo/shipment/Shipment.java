package com.truckgo.shipment;

import com.truckgo.common.exception.ConflictException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Aggregate root. Totals are always derived from the items; status only changes through {@link #transitionTo}. */
@Entity
@Table(name = "shipments")
@SQLRestriction("deleted_at IS NULL")
public class Shipment {
    @Id private UUID id;
    @Column(name = "shipment_code", nullable = false, updatable = false) private String shipmentCode;
    @Column(name = "customer_id", nullable = false, updatable = false) private UUID customerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ShipmentStatus status;
    @Column(name = "pickup_time") private Instant pickupTime;
    @Column(name = "delivery_deadline") private Instant deliveryDeadline;
    @Column(name = "vehicle_type_code") private String vehicleTypeCode;
    @Column(name = "body_type_code") private String bodyTypeCode;
    @Column(name = "vehicle_count", nullable = false) private int vehicleCount = 1;
    @Column(name = "minimum_payload_kg") private BigDecimal minimumPayloadKg;
    @Column(name = "min_cargo_length_cm") private BigDecimal minCargoLengthCm;
    @Column(name = "min_cargo_width_cm") private BigDecimal minCargoWidthCm;
    @Column(name = "min_cargo_height_cm") private BigDecimal minCargoHeightCm;
    @Column(name = "total_quantity", nullable = false) private int totalQuantity;
    @Column(name = "total_weight_kg", nullable = false) private BigDecimal totalWeightKg = BigDecimal.ZERO;
    @Column(name = "total_volume_m3", nullable = false) private BigDecimal totalVolumeM3 = BigDecimal.ZERO;
    @Column(name = "customer_note") private String customerNote;
    /** Total of the accepted quote (Sprint 4); null until the customer accepts a quote. */
    @Column(name = "estimated_price") private BigDecimal estimatedPrice;
    @Column(name = "final_price") private BigDecimal finalPrice;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "cancellation_reason") private String cancellationReason;
    @Column(name = "deleted_at") private Instant deletedAt;
    /** Driver profile and vehicle chosen by the customer (Sprint 5); set only together with ASSIGNED. */
    @Column(name = "assigned_driver_id") private UUID assignedDriverId;
    @Column(name = "assigned_vehicle_id") private UUID assignedVehicleId;
    @Column(name = "assigned_at") private Instant assignedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("sequenceNo") @BatchSize(size = 50)
    private List<ShipmentStop> stops = new ArrayList<>();
    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("createdAt, id") @BatchSize(size = 50)
    private List<ShipmentItem> items = new ArrayList<>();
    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("serviceCode") @BatchSize(size = 50)
    private List<ShipmentExtraService> services = new ArrayList<>();

    protected Shipment() { }

    public Shipment(String shipmentCode, UUID customerId) {
        this.id = UUID.randomUUID();
        this.shipmentCode = shipmentCode;
        this.customerId = customerId;
        this.status = ShipmentStatus.DRAFT;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public void setSchedule(Instant pickupTime, Instant deliveryDeadline) { this.pickupTime = pickupTime; this.deliveryDeadline = deliveryDeadline; }

    public void setVehicleRequirement(String vehicleTypeCode, String bodyTypeCode, int vehicleCount, BigDecimal minimumPayloadKg,
                                      BigDecimal minLength, BigDecimal minWidth, BigDecimal minHeight) {
        this.vehicleTypeCode = vehicleTypeCode;
        this.bodyTypeCode = bodyTypeCode;
        this.vehicleCount = vehicleCount;
        this.minimumPayloadKg = minimumPayloadKg;
        this.minCargoLengthCm = minLength;
        this.minCargoWidthCm = minWidth;
        this.minCargoHeightCm = minHeight;
    }

    public void setCustomerNote(String customerNote) { this.customerNote = customerNote; }

    /** Detaches every stop, item and service; the caller flushes before adding replacements (unique keys). */
    public void clearDetails() { stops.clear(); items.clear(); services.clear(); }

    public void addStop(ShipmentStop stop) { stop.attachTo(this); stops.add(stop); }
    public void addItem(ShipmentItem item) { item.attachTo(this); items.add(item); }
    public void addService(ShipmentExtraService service) { service.attachTo(this); services.add(service); }

    /** Recomputes totals from item rows; values sent by the client are never used. */
    public void recalculateTotals() {
        totalQuantity = items.stream().mapToInt(ShipmentItem::getQuantity).sum();
        totalWeightKg = items.stream().map(ShipmentItem::getTotalWeightKg).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CargoCalculator.WEIGHT_SCALE);
        totalVolumeM3 = items.stream().map(ShipmentItem::getTotalVolumeM3).filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CargoCalculator.VOLUME_SCALE);
    }

    public void transitionTo(ShipmentStatus target) {
        if (!status.canTransitionTo(target)) throw new ConflictException("Không thể chuyển đơn từ trạng thái " + status + " sang " + target);
        status = target;
    }

    public void markPublished(Instant at) { transitionTo(ShipmentStatus.PUBLISHED); publishedAt = at; }

    public void markCancelled(Instant at, String reason) { transitionTo(ShipmentStatus.CANCELLED); cancelledAt = at; cancellationReason = reason; }

    /** Nobody was chosen before the pickup time. */
    public void markExpired() { transitionTo(ShipmentStatus.EXPIRED); }

    public boolean isAssigned() { return assignedDriverId != null; }

    public void markDeleted(Instant at) {
        if (status != ShipmentStatus.DRAFT) throw new ConflictException("Chỉ có thể xóa đơn nháp");
        deletedAt = at;
    }

    /** Price agreed by the customer (the accepted quote's total); cleared when the order changes. */
    public void setEstimatedPrice(BigDecimal estimatedPrice) { this.estimatedPrice = estimatedPrice; }

    public boolean isOwnedBy(UUID userId) { return customerId.equals(userId); }

    public UUID getId() { return id; }
    public String getShipmentCode() { return shipmentCode; }
    public UUID getCustomerId() { return customerId; }
    public ShipmentStatus getStatus() { return status; }
    public Instant getPickupTime() { return pickupTime; }
    public Instant getDeliveryDeadline() { return deliveryDeadline; }
    public String getVehicleTypeCode() { return vehicleTypeCode; }
    public String getBodyTypeCode() { return bodyTypeCode; }
    public int getVehicleCount() { return vehicleCount; }
    public BigDecimal getMinimumPayloadKg() { return minimumPayloadKg; }
    public BigDecimal getMinCargoLengthCm() { return minCargoLengthCm; }
    public BigDecimal getMinCargoWidthCm() { return minCargoWidthCm; }
    public BigDecimal getMinCargoHeightCm() { return minCargoHeightCm; }
    public int getTotalQuantity() { return totalQuantity; }
    public BigDecimal getTotalWeightKg() { return totalWeightKg; }
    public BigDecimal getTotalVolumeM3() { return totalVolumeM3; }
    public String getCustomerNote() { return customerNote; }
    public BigDecimal getEstimatedPrice() { return estimatedPrice; }
    public BigDecimal getFinalPrice() { return finalPrice; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancellationReason() { return cancellationReason; }
    public UUID getAssignedDriverId() { return assignedDriverId; }
    public UUID getAssignedVehicleId() { return assignedVehicleId; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public List<ShipmentStop> getStops() { return stops; }
    public List<ShipmentItem> getItems() { return items; }
    public List<ShipmentExtraService> getServices() { return services; }
}
