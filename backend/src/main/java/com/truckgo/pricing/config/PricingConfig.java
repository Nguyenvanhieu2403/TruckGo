package com.truckgo.pricing.config;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Price table for one vehicle type over an effective period. Quotes keep a snapshot, so edits never change them. */
@Entity
@Table(name = "pricing_configs")
public class PricingConfig {
    @Id private UUID id;
    @Column(nullable = false) private String name;
    @Column(name = "vehicle_type_code", nullable = false) private String vehicleTypeCode;
    @Column(name = "base_price", nullable = false) private BigDecimal basePrice;
    @Column(name = "price_per_km", nullable = false) private BigDecimal pricePerKm;
    @Column(name = "included_distance_km", nullable = false) private BigDecimal includedDistanceKm;
    @Column(name = "included_weight_kg", nullable = false) private BigDecimal includedWeightKg;
    @Column(name = "price_per_extra_kg", nullable = false) private BigDecimal pricePerExtraKg;
    @Column(name = "included_volume_m3", nullable = false) private BigDecimal includedVolumeM3;
    @Column(name = "price_per_extra_m3", nullable = false) private BigDecimal pricePerExtraM3;
    @Column(name = "additional_stop_price", nullable = false) private BigDecimal additionalStopPrice;
    @Column(name = "minimum_price", nullable = false) private BigDecimal minimumPrice;
    @Column(nullable = false, columnDefinition = "bpchar(3)") private String currency = "VND";
    @Column(name = "effective_from", nullable = false) private Instant effectiveFrom;
    @Column(name = "effective_to") private Instant effectiveTo;
    @Column(name = "is_active", nullable = false) private boolean active;
    @Column(name = "created_by", nullable = false, updatable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
    @OneToMany(mappedBy = "config", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("name")
    private List<PricingTimeRule> timeRules = new ArrayList<>();
    @OneToMany(mappedBy = "config", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("code")
    private List<PricingExtraFee> extraFees = new ArrayList<>();

    protected PricingConfig() { }

    public PricingConfig(UUID createdBy) {
        this.id = UUID.randomUUID();
        this.createdBy = createdBy;
        this.active = true;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public void apply(String name, String vehicleTypeCode, BigDecimal basePrice, BigDecimal pricePerKm, BigDecimal includedDistanceKm, BigDecimal includedWeightKg,
                      BigDecimal pricePerExtraKg, BigDecimal includedVolumeM3, BigDecimal pricePerExtraM3, BigDecimal additionalStopPrice, BigDecimal minimumPrice,
                      Instant effectiveFrom, Instant effectiveTo) {
        this.name = name;
        this.vehicleTypeCode = vehicleTypeCode;
        this.basePrice = basePrice;
        this.pricePerKm = pricePerKm;
        this.includedDistanceKm = includedDistanceKm;
        this.includedWeightKg = includedWeightKg;
        this.pricePerExtraKg = pricePerExtraKg;
        this.includedVolumeM3 = includedVolumeM3;
        this.pricePerExtraM3 = pricePerExtraM3;
        this.additionalStopPrice = additionalStopPrice;
        this.minimumPrice = minimumPrice;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }

    public void replaceRules(List<PricingTimeRule> rules, List<PricingExtraFee> fees) {
        timeRules.clear();
        extraFees.clear();
        rules.forEach(r -> { r.attachTo(this); timeRules.add(r); });
        fees.forEach(f -> { f.attachTo(this); extraFees.add(f); });
    }

    public boolean isEffectiveAt(Instant at) { return active && !effectiveFrom.isAfter(at) && (effectiveTo == null || effectiveTo.isAfter(at)); }

    public void setActive(boolean active) { this.active = active; }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getVehicleTypeCode() { return vehicleTypeCode; }
    public BigDecimal getBasePrice() { return basePrice; }
    public BigDecimal getPricePerKm() { return pricePerKm; }
    public BigDecimal getIncludedDistanceKm() { return includedDistanceKm; }
    public BigDecimal getIncludedWeightKg() { return includedWeightKg; }
    public BigDecimal getPricePerExtraKg() { return pricePerExtraKg; }
    public BigDecimal getIncludedVolumeM3() { return includedVolumeM3; }
    public BigDecimal getPricePerExtraM3() { return pricePerExtraM3; }
    public BigDecimal getAdditionalStopPrice() { return additionalStopPrice; }
    public BigDecimal getMinimumPrice() { return minimumPrice; }
    public String getCurrency() { return currency; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
    public boolean isActive() { return active; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public List<PricingTimeRule> getTimeRules() { return timeRules; }
    public List<PricingExtraFee> getExtraFees() { return extraFees; }
}
