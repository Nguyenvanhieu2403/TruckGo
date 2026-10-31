package com.truckgo.trip;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryConfirmationRepository extends JpaRepository<DeliveryConfirmation, UUID> {
    /** The latest challenge of an order, locked so concurrent guesses are counted one by one. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DeliveryConfirmation> findFirstByShipmentIdOrderByCreatedAtDesc(UUID shipmentId);

    Optional<DeliveryConfirmation> findTopByShipmentIdOrderByCreatedAtDesc(UUID shipmentId);

    @Query("select c.shipmentId from DeliveryConfirmation c where c.status = com.truckgo.trip.DeliveryConfirmation.Status.PENDING and c.expiresAt <= :now")
    List<UUID> findShipmentsWithExpiredPending(@Param("now") Instant now);
}
