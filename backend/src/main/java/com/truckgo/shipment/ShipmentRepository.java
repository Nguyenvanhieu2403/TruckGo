package com.truckgo.shipment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/** Soft-deleted drafts are excluded by {@code @SQLRestriction} on {@link Shipment}. */
public interface ShipmentRepository extends JpaRepository<Shipment, UUID>, JpaSpecificationExecutor<Shipment> {
    boolean existsByShipmentCode(String shipmentCode);
}
