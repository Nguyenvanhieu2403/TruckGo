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
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "pricing_time_rules")
public class PricingTimeRule {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pricing_config_id") private PricingConfig config;
    @Column(nullable = false) private String name;
    /** Comma separated MON..SUN; null = every day. */
    @Column(name = "days_of_week") private String daysOfWeek;
    @Column(name = "start_time", nullable = false) private LocalTime startTime;
    @Column(name = "end_time", nullable = false) private LocalTime endTime;
    @Enumerated(EnumType.STRING) @Column(name = "fee_type", nullable = false) private FeeType feeType;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private BigDecimal percentage;

    protected PricingTimeRule() { }

    public PricingTimeRule(String name, String daysOfWeek, LocalTime startTime, LocalTime endTime, FeeType feeType, BigDecimal amount, BigDecimal percentage) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.daysOfWeek = daysOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.feeType = feeType;
        this.amount = amount;
        this.percentage = percentage;
    }

    void attachTo(PricingConfig config) { this.config = config; }

    public String getName() { return name; }
    public String getDaysOfWeek() { return daysOfWeek; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public FeeType getFeeType() { return feeType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getPercentage() { return percentage; }
}
