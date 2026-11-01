package com.truckgo.marketplace;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentAssignmentRepository extends JpaRepository<ShipmentAssignment, UUID> {
    List<ShipmentAssignment> findByShipmentIdOrderByAssignedAtAsc(UUID shipmentId);

    List<ShipmentAssignment> findByShipmentIdAndStatusOrderBySlotNoAsc(UUID shipmentId, AssignmentStatus status);

    List<ShipmentAssignment> findByShipmentIdInAndStatus(Collection<UUID> shipmentIds, AssignmentStatus status);

    long countByShipmentIdAndStatus(UUID shipmentId, AssignmentStatus status);

    Optional<ShipmentAssignment> findFirstByShipmentIdAndDriverIdAndStatus(UUID shipmentId, UUID driverId, AssignmentStatus status);

    boolean existsByShipmentIdAndDriverIdAndStatus(UUID shipmentId, UUID driverId, AssignmentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ShipmentAssignment a where a.id = :id")
    Optional<ShipmentAssignment> findByIdForUpdate(@Param("id") UUID id);
}
