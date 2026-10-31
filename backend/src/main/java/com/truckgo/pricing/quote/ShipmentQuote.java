package com.truckgo.pricing.quote;

import com.truckgo.common.exception.ConflictException;
import com.truckgo.pricing.engine.PriceComponent;
import com.truckgo.pricing.engine.PricingResult;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One immutable version of a shipment's price. Amounts and the snapshot are written once at creation; afterwards only
 * the status (and its timestamps) changes. A new price always means a new version.
 */
@Entity
@Table(name = "shipment_quotes")
public class ShipmentQuote {
    @Id private UUID id;
    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "quote_code", nullable = false, updatable = false) private String quoteCode;
    @Column(name = "quote_version", nullable = false, updatable = false) private int quoteVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private QuoteStatus status;
    @Column(name = "pricing_config_id", updatable = false) private UUID pricingConfigId;
    @Column(name = "vehicle_type_code", updatable = false) private String vehicleTypeCode;
    @Column(name = "vehicle_count", nullable = false, updatable = false) private int vehicleCount;
    @Column(name = "distance_km", updatable = false) private BigDecimal distanceKm;
    @Column(name = "distance_source", updatable = false) private String distanceSource;
    @Column(name = "route_provider", updatable = false) private String routeProvider;
    @Column(name = "route_duration_minutes", updatable = false) private Integer routeDurationMinutes;
    @Column(name = "base_price", updatable = false) private BigDecimal basePrice;
    @Column(name = "distance_price", updatable = false) private BigDecimal distancePrice;
    @Column(name = "weight_surcharge", updatable = false) private BigDecimal weightSurcharge;
    @Column(name = "volume_surcharge", updatable = false) private BigDecimal volumeSurcharge;
    @Column(name = "service_surcharge", updatable = false) private BigDecimal serviceSurcharge;
    @Column(name = "additional_stop_surcharge", updatable = false) private BigDecimal additionalStopSurcharge;
    @Column(name = "time_surcharge", updatable = false) private BigDecimal timeSurcharge;
    @Column(name = "other_surcharge", updatable = false) private BigDecimal otherSurcharge;
    @Column(name = "total_price", updatable = false) private BigDecimal totalPrice;
    @Column(nullable = false, updatable = false, columnDefinition = "bpchar(3)") private String currency = "VND";
    @Column(name = "review_reason_code", updatable = false) private String reviewReasonCode;
    @Column(name = "review_reason", updatable = false) private String reviewReason;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "adjustment_reason", updatable = false) private String adjustmentReason;
    @Column(name = "adjusted_by", updatable = false) private UUID adjustedBy;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "pricing_snapshot", updatable = false) private String pricingSnapshot;
    @Column(name = "created_by", nullable = false, updatable = false) private UUID createdBy;
    @Column(name = "expires_at", updatable = false) private Instant expiresAt;
    @Column(name = "accepted_at") private Instant acceptedAt;
    @Column(name = "rejected_at") private Instant rejectedAt;
    @Column(name = "superseded_at") private Instant supersededAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
    @OneToMany(mappedBy = "quote", cascade = {CascadeType.PERSIST, CascadeType.MERGE}) @OrderBy("lineNo")
    private List<ShipmentQuoteItem> items = new ArrayList<>();

    protected ShipmentQuote() { }

    ShipmentQuote(UUID shipmentId, String shipmentCode, int quoteVersion, UUID createdBy, String vehicleTypeCode, int vehicleCount) {
        this.id = UUID.randomUUID();
        this.shipmentId = shipmentId;
        this.quoteVersion = quoteVersion;
        this.quoteCode = shipmentCode + "-Q" + quoteVersion;
        this.createdBy = createdBy;
        this.vehicleTypeCode = vehicleTypeCode;
        this.vehicleCount = vehicleCount;
        this.status = QuoteStatus.PENDING;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    void distance(BigDecimal km, String source, String provider, Integer minutes) {
        this.distanceKm = km;
        this.distanceSource = source;
        this.routeProvider = provider;
        this.routeDurationMinutes = minutes;
    }

    void adjustment(UUID adminId, String reason) { this.adjustedBy = adminId; this.adjustmentReason = reason; }

    void snapshot(String json) { this.pricingSnapshot = json; }

    void awaitReview(String code, String reason) {
        status = QuoteStatus.AWAITING_MANUAL_REVIEW;
        reviewReasonCode = code;
        reviewReason = reason;
    }

    void priced(UUID configId, PricingResult result, Instant expiresAt) {
        this.pricingConfigId = configId;
        this.basePrice = result.component(PriceComponent.BASE);
        this.distancePrice = result.component(PriceComponent.DISTANCE);
        this.weightSurcharge = result.component(PriceComponent.WEIGHT);
        this.volumeSurcharge = result.component(PriceComponent.VOLUME);
        this.serviceSurcharge = result.component(PriceComponent.SERVICE);
        this.additionalStopSurcharge = result.component(PriceComponent.STOP);
        this.timeSurcharge = result.component(PriceComponent.TIME);
        this.otherSurcharge = result.component(PriceComponent.OTHER);
        this.totalPrice = result.total();
        this.expiresAt = expiresAt;
        Instant now = Instant.now();
        for (int i = 0; i < result.lines().size(); i++) items.add(new ShipmentQuoteItem(this, i + 1, result.lines().get(i), now));
        this.status = QuoteStatus.CALCULATED;
    }

    public boolean isExpiredAt(Instant at) { return status == QuoteStatus.CALCULATED && expiresAt != null && !expiresAt.isAfter(at); }

    void markExpired() { requireStatus(QuoteStatus.CALCULATED); status = QuoteStatus.EXPIRED; }

    void markSuperseded(Instant at) {
        if (!status.isCurrent()) return;
        status = QuoteStatus.SUPERSEDED;
        supersededAt = at;
    }

    void markAccepted(Instant at) { requireStatus(QuoteStatus.CALCULATED); status = QuoteStatus.ACCEPTED; acceptedAt = at; }

    void markRejected(Instant at, String reason) { requireStatus(QuoteStatus.CALCULATED); status = QuoteStatus.REJECTED; rejectedAt = at; rejectionReason = reason; }

    private void requireStatus(QuoteStatus expected) {
        if (status != expected) throw new ConflictException("Báo giá đang ở trạng thái " + status + ", không thể thực hiện thao tác này");
    }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public String getQuoteCode() { return quoteCode; }
    public int getQuoteVersion() { return quoteVersion; }
    public QuoteStatus getStatus() { return status; }
    public UUID getPricingConfigId() { return pricingConfigId; }
    public String getVehicleTypeCode() { return vehicleTypeCode; }
    public int getVehicleCount() { return vehicleCount; }
    public BigDecimal getDistanceKm() { return distanceKm; }
    public String getDistanceSource() { return distanceSource; }
    public String getRouteProvider() { return routeProvider; }
    public Integer getRouteDurationMinutes() { return routeDurationMinutes; }
    public BigDecimal getBasePrice() { return basePrice; }
    public BigDecimal getDistancePrice() { return distancePrice; }
    public BigDecimal getWeightSurcharge() { return weightSurcharge; }
    public BigDecimal getVolumeSurcharge() { return volumeSurcharge; }
    public BigDecimal getServiceSurcharge() { return serviceSurcharge; }
    public BigDecimal getAdditionalStopSurcharge() { return additionalStopSurcharge; }
    public BigDecimal getTimeSurcharge() { return timeSurcharge; }
    public BigDecimal getOtherSurcharge() { return otherSurcharge; }
    public BigDecimal getTotalPrice() { return totalPrice; }
    public String getCurrency() { return currency; }
    public String getReviewReasonCode() { return reviewReasonCode; }
    public String getReviewReason() { return reviewReason; }
    public String getRejectionReason() { return rejectionReason; }
    public String getAdjustmentReason() { return adjustmentReason; }
    public UUID getAdjustedBy() { return adjustedBy; }
    public String getPricingSnapshot() { return pricingSnapshot; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getRejectedAt() { return rejectedAt; }
    public Instant getSupersededAt() { return supersededAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<ShipmentQuoteItem> getItems() { return items; }
}
