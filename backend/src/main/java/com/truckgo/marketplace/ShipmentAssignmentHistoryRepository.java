package com.truckgo.marketplace;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/** Insert and read only. */
public interface ShipmentAssignmentHistoryRepository extends Repository<ShipmentAssignmentHistory, UUID> {
    ShipmentAssignmentHistory save(ShipmentAssignmentHistory history);
    List<ShipmentAssignmentHistory> findByShipmentIdOrderByCreatedAtAsc(UUID shipmentId);
    Page<ShipmentAssignmentHistory> findAll(Pageable pageable);
}
