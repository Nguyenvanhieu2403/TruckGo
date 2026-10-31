package com.truckgo.pricing.config;

import com.truckgo.pricing.engine.FeeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Fee of a Sprint 3 catalog service under one price table; deletion is a soft deactivate. */
@Entity
@Table(name = "pricing_service_fees")
public class PricingServiceFee {
    @Id private UUID id;
    @Column(name = "pricing_config_id", nullable = false, updatable = false) private UUID pricingConfigId;
    @Column(name = "service_code", nullable = false) private String serviceCode;
    @Column(name = "service_option_value") private String serviceOptionValue;
    @Enumerated(EnumType.STRING) @Column(name = "pricing_type", nullable = false) private FeeType pricingType;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private BigDecimal percentage;
    @Column(name = "is_active", nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected PricingServiceFee() { }

    public PricingServiceFee(UUID pricingConfigId) {
        this.id = UUID.randomUUID();
        this.pricingConfigId = pricingConfigId;
        this.active = true;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public void apply(String serviceCode, String optionValue, FeeType type, BigDecimal amount, BigDecimal percentage) {
        this.serviceCode = serviceCode;
        this.serviceOptionValue = optionValue;
        this.pricingType = type;
        this.amount = amount;
        this.percentage = percentage;
    }

    public void deactivate() { this.active = false; }
    public UUID getId() { return id; }
    public UUID getPricingConfigId() { return pricingConfigId; }
    public String getServiceCode() { return serviceCode; }
    public String getServiceOptionValue() { return serviceOptionValue; }
    public FeeType getPricingType() { return pricingType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getPercentage() { return percentage; }
    public boolean isActive() { return active; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
