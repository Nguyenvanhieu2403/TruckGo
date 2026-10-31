package com.truckgo.pricing.config;

import com.truckgo.pricing.engine.FeeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "pricing_extra_fees")
public class PricingExtraFee {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pricing_config_id") private PricingConfig config;
    @Column(nullable = false) private String code;
    @Column(nullable = false) private String name;
    @Enumerated(EnumType.STRING) @Column(name = "fee_type", nullable = false) private FeeType feeType;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private BigDecimal percentage;

    protected PricingExtraFee() { }

    public PricingExtraFee(String code, String name, FeeType feeType, BigDecimal amount, BigDecimal percentage) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name;
        this.feeType = feeType;
        this.amount = amount;
        this.percentage = percentage;
    }

    void attachTo(PricingConfig config) { this.config = config; }

    public String getCode() { return code; }
    public String getName() { return name; }
    public FeeType getFeeType() { return feeType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getPercentage() { return percentage; }
}
