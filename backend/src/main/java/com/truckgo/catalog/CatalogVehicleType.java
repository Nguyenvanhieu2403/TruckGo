package com.truckgo.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payload class the customer can request ("Xe tải 1 tấn"). Distinct from {@code vehicle.VehicleType}, which is the
 * body style of a driver's registered vehicle. Nominal payload only; legal capacity is verified when matching.
 */
@Entity
@Table(name = "vehicle_types")
public class CatalogVehicleType implements CatalogEntry {
    @Id private String code;
    @Column(nullable = false) private String name;
    @Column(name = "max_payload_kg", nullable = false) private BigDecimal maxPayloadKg;
    @Column(name = "cargo_length_cm") private BigDecimal cargoLengthCm;
    @Column(name = "cargo_width_cm") private BigDecimal cargoWidthCm;
    @Column(name = "cargo_height_cm") private BigDecimal cargoHeightCm;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(nullable = false) private boolean active;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected CatalogVehicleType() { }

    @Override public String getCode() { return code; }
    public String getName() { return name; }
    public BigDecimal getMaxPayloadKg() { return maxPayloadKg; }
    public BigDecimal getCargoLengthCm() { return cargoLengthCm; }
    public BigDecimal getCargoWidthCm() { return cargoWidthCm; }
    public BigDecimal getCargoHeightCm() { return cargoHeightCm; }
    public int getSortOrder() { return sortOrder; }
    @Override public boolean isActive() { return active; }
    @Override public void setActive(boolean active) { this.active = active; this.updatedAt = Instant.now(); }
}
