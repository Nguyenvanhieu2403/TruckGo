package com.truckgo.shipment;

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
import java.time.Instant;
import java.util.UUID;

/** Pickup or delivery point. Any number of stops per order is supported; ordering is by sequenceNo. */
@Entity
@Table(name = "shipment_stops")
public class ShipmentStop {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "shipment_id") private Shipment shipment;
    @Enumerated(EnumType.STRING) @Column(name = "stop_type", nullable = false) private StopType stopType;
    @Column(name = "sequence_no", nullable = false) private int sequenceNo;
    @Column(name = "contact_name", nullable = false) private String contactName;
    @Column(name = "contact_phone", nullable = false) private String contactPhone;
    @Column(nullable = false) private String province;
    private String district;
    private String ward;
    @Column(name = "address_detail", nullable = false) private String addressDetail;
    @Column(name = "full_address", nullable = false) private String fullAddress;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String note;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ShipmentStop() { }

    public ShipmentStop(StopType stopType, int sequenceNo, String contactName, String contactPhone, String province, String district, String ward,
                        String addressDetail, String fullAddress, BigDecimal latitude, BigDecimal longitude, String note) {
        this.id = UUID.randomUUID();
        this.stopType = stopType;
        this.sequenceNo = sequenceNo;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.province = province;
        this.district = district;
        this.ward = ward;
        this.addressDetail = addressDetail;
        this.fullAddress = fullAddress;
        this.latitude = latitude;
        this.longitude = longitude;
        this.note = note;
        this.createdAt = Instant.now();
    }

    void attachTo(Shipment shipment) { this.shipment = shipment; }

    public UUID getId() { return id; }
    public StopType getStopType() { return stopType; }
    public int getSequenceNo() { return sequenceNo; }
    public String getContactName() { return contactName; }
    public String getContactPhone() { return contactPhone; }
    public String getProvince() { return province; }
    public String getDistrict() { return district; }
    public String getWard() { return ward; }
    public String getAddressDetail() { return addressDetail; }
    public String getFullAddress() { return fullAddress; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public String getNote() { return note; }
}
