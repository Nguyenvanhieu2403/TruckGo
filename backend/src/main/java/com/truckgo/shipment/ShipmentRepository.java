package com.truckgo.shipment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Soft-deleted drafts are excluded by {@code @SQLRestriction} on {@link Shipment}. */
public interface ShipmentRepository extends JpaRepository<Shipment, UUID>, JpaSpecificationExecutor<Shipment> {
    boolean existsByShipmentCode(String shipmentCode);

    /** Row lock that serialises concurrent pricing of the same shipment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.id = :id")
    Optional<Shipment> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Conditional assignment: only succeeds while the order is still open, so even a caller that skipped the row lock
     * can never overwrite an existing driver. Returns the number of rows changed (0 or 1).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Shipment s set s.status = com.truckgo.shipment.ShipmentStatus.ASSIGNED, s.assignedDriverId = :driverId,
                   s.assignedVehicleId = :vehicleId, s.assignedAt = :at, s.updatedAt = :at, s.version = s.version + 1
             where s.id = :id and s.status = com.truckgo.shipment.ShipmentStatus.PUBLISHED and s.assignedDriverId is null""")
    int assignIfOpen(@Param("id") UUID id, @Param("driverId") UUID driverId, @Param("vehicleId") UUID vehicleId, @Param("at") Instant at);

    /** Published orders nobody was assigned to before pickup (expiry job). */
    @Query("select s.id from Shipment s where s.status = com.truckgo.shipment.ShipmentStatus.PUBLISHED and s.assignedDriverId is null and s.pickupTime <= :at")
    List<UUID> findOpenPickupBefore(@Param("at") Instant at);

    long countByAssignedDriverIdAndStatus(UUID assignedDriverId, ShipmentStatus status);
}
