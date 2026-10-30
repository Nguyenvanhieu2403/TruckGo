package com.truckgo.shipment;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/** Insert and read only: no update or delete methods are exposed. */
public interface ShipmentStatusHistoryRepository extends Repository<ShipmentStatusHistory, UUID> {
    ShipmentStatusHistory save(ShipmentStatusHistory history);
    List<ShipmentStatusHistory> findByShipmentIdOrderByCreatedAtAsc(UUID shipmentId);
}
