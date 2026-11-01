package com.truckgo.shipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A service ordered at one stop (e.g. unloading at the second delivery), priced once per stop. */
@Entity
@Table(name = "shipment_stop_services")
public class ShipmentStopService {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "shipment_stop_id") private ShipmentStop stop;
    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "service_code", nullable = false) private String serviceCode;
    @Column(name = "service_name", nullable = false) private String serviceName;
    @Column(name = "service_value", nullable = false) private String serviceValue;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ShipmentStopService() { }

    public ShipmentStopService(String serviceCode, String serviceName, String serviceValue) {
        this.id = UUID.randomUUID();
        this.serviceCode = serviceCode;
        this.serviceName = serviceName;
        this.serviceValue = serviceValue;
        this.createdAt = Instant.now();
    }

    void attachTo(ShipmentStop stop) { this.stop = stop; }

    @PrePersist void beforeInsert() { if (shipmentId == null) shipmentId = stop.shipmentId(); }

    public UUID getId() { return id; }
    public String getServiceCode() { return serviceCode; }
    public String getServiceName() { return serviceName; }
    public String getServiceValue() { return serviceValue; }
}
