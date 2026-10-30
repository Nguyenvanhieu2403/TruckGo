package com.truckgo.shipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shipment_items")
public class ShipmentItem {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "shipment_id") private Shipment shipment;
    @Column(name = "item_name", nullable = false) private String itemName;
    @Column(name = "category_code", nullable = false) private String categoryCode;
    private String description;
    @Column(nullable = false) private int quantity;
    @Column(name = "weight_per_unit_kg", nullable = false) private BigDecimal weightPerUnitKg;
    @Column(name = "length_cm") private BigDecimal lengthCm;
    @Column(name = "width_cm") private BigDecimal widthCm;
    @Column(name = "height_cm") private BigDecimal heightCm;
    @Column(name = "total_weight_kg", nullable = false) private BigDecimal totalWeightKg;
    @Column(name = "total_volume_m3") private BigDecimal totalVolumeM3;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ShipmentItem() { }

    /** Totals are computed here from the line data and never accepted from the client. */
    public ShipmentItem(String itemName, String categoryCode, String description, int quantity, BigDecimal weightPerUnitKg,
                        BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.itemName = itemName;
        this.categoryCode = categoryCode;
        this.description = description;
        this.quantity = quantity;
        this.weightPerUnitKg = weightPerUnitKg;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.totalWeightKg = CargoCalculator.itemWeight(quantity, weightPerUnitKg);
        this.totalVolumeM3 = CargoCalculator.itemVolume(quantity, lengthCm, widthCm, heightCm);
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    void attachTo(Shipment shipment) { this.shipment = shipment; }

    public boolean hasDimensions() { return lengthCm != null && widthCm != null && heightCm != null; }

    public UUID getId() { return id; }
    public String getItemName() { return itemName; }
    public String getCategoryCode() { return categoryCode; }
    public String getDescription() { return description; }
    public int getQuantity() { return quantity; }
    public BigDecimal getWeightPerUnitKg() { return weightPerUnitKg; }
    public BigDecimal getLengthCm() { return lengthCm; }
    public BigDecimal getWidthCm() { return widthCm; }
    public BigDecimal getHeightCm() { return heightCm; }
    public BigDecimal getTotalWeightKg() { return totalWeightKg; }
    public BigDecimal getTotalVolumeM3() { return totalVolumeM3; }
}
