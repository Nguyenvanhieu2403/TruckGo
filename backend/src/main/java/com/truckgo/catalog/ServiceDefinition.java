package com.truckgo.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "shipment_service_definitions")
public class ServiceDefinition implements CatalogEntry {
    @Id private String code;
    @Column(nullable = false) private String name;
    @Column(name = "group_code", nullable = false) private String groupCode;
    @Enumerated(EnumType.STRING) @Column(name = "value_type", nullable = false) private ServiceValueType valueType;
    private String description;
    @Column(name = "exclusive_group") private String exclusiveGroup;
    @Column(name = "min_value") private Integer minValue;
    @Column(name = "max_value") private Integer maxValue;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(nullable = false) private boolean active;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    /** ORDER (whole order), STOP (individual stops) or BOTH; stopTypes says which stops: PICKUP, DELIVERY or ANY. */
    @Column(name = "applies_to", nullable = false) private String appliesTo;
    @Column(name = "stop_types") private String stopTypes;
    @OneToMany(fetch = FetchType.EAGER) @JoinColumn(name = "service_code", referencedColumnName = "code", insertable = false, updatable = false)
    @OrderBy("sortOrder") private List<ServiceOption> options = new ArrayList<>();

    protected ServiceDefinition() { }

    @Override public String getCode() { return code; }
    public String getName() { return name; }
    public String getGroupCode() { return groupCode; }
    public ServiceValueType getValueType() { return valueType; }
    public String getDescription() { return description; }
    public String getExclusiveGroup() { return exclusiveGroup; }
    public Integer getMinValue() { return minValue; }
    public Integer getMaxValue() { return maxValue; }
    public int getSortOrder() { return sortOrder; }
    public List<ServiceOption> getOptions() { return options; }
    public String getAppliesTo() { return appliesTo; }
    public String getStopTypes() { return stopTypes; }
    @Override public boolean isActive() { return active; }
    @Override public void setActive(boolean active) { this.active = active; this.updatedAt = Instant.now(); }
}
