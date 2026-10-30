package com.truckgo.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "vehicle_body_types")
public class VehicleBodyType implements CatalogEntry {
    @Id private String code;
    @Column(nullable = false) private String name;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(nullable = false) private boolean active;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected VehicleBodyType() { }

    @Override public String getCode() { return code; }
    public String getName() { return name; }
    public int getSortOrder() { return sortOrder; }
    @Override public boolean isActive() { return active; }
    @Override public void setActive(boolean active) { this.active = active; this.updatedAt = Instant.now(); }
}
