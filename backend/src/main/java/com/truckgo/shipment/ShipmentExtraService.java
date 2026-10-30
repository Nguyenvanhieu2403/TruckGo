package com.truckgo.shipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A selected extra service. Name and description are copied from the catalog, never from the request. */
@Entity
@Table(name = "shipment_services")
public class ShipmentExtraService {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "shipment_id") private Shipment shipment;
    @Column(name = "service_code", nullable = false) private String serviceCode;
    @Column(name = "service_name", nullable = false) private String serviceName;
    @Column(name = "service_value", nullable = false) private String serviceValue;
    private String description;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ShipmentExtraService() { }

    public ShipmentExtraService(String serviceCode, String serviceName, String serviceValue, String description) {
        this.id = UUID.randomUUID();
        this.serviceCode = serviceCode;
        this.serviceName = serviceName;
        this.serviceValue = serviceValue;
        this.description = description;
        this.createdAt = Instant.now();
    }

    void attachTo(Shipment shipment) { this.shipment = shipment; }

    public String getServiceCode() { return serviceCode; }
    public String getServiceName() { return serviceName; }
    public String getServiceValue() { return serviceValue; }
    public String getDescription() { return description; }
}
