package com.truckgo.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;

@Entity
@Table(name = "shipment_service_options")
@IdClass(ServiceOption.Key.class)
public class ServiceOption {
    public record Key(String serviceCode, String value) implements Serializable { public Key() { this(null, null); } }

    @Id @Column(name = "service_code") private String serviceCode;
    @Id private String value;
    @Column(nullable = false) private String label;
    @Column(name = "sort_order", nullable = false) private int sortOrder;

    protected ServiceOption() { }

    public String getServiceCode() { return serviceCode; }
    public String getValue() { return value; }
    public String getLabel() { return label; }
    public int getSortOrder() { return sortOrder; }
}
