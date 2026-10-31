package com.truckgo.pricing.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Append-only change log of a price table (including its service fees). */
@Entity
@Immutable
@Table(name = "pricing_config_histories")
public class PricingConfigHistory {
    @Id private UUID id;
    @Column(name = "pricing_config_id", nullable = false) private UUID pricingConfigId;
    @Column(nullable = false) private String action;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "old_value") private String oldValue;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "new_value") private String newValue;
    @Column(name = "changed_by", nullable = false) private UUID changedBy;
    @Column(name = "changed_at", nullable = false) private Instant changedAt;

    protected PricingConfigHistory() { }

    public PricingConfigHistory(UUID pricingConfigId, String action, String oldValue, String newValue, UUID changedBy) {
        this.id = UUID.randomUUID();
        this.pricingConfigId = pricingConfigId;
        this.action = action;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.changedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getAction() { return action; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
}
