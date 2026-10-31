package com.truckgo.trip;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Insert, read and retention delete only. */
public interface TrackingLocationRepository extends Repository<TrackingLocation, UUID> {
    TrackingLocation saveAndFlush(TrackingLocation location);

    Optional<TrackingLocation> findFirstByShipmentIdOrderByRecordedAtDesc(UUID shipmentId);

    boolean existsByShipmentIdAndRecordedAt(UUID shipmentId, Instant recordedAt);

    Page<TrackingLocation> findByShipmentIdAndRecordedAtBetween(UUID shipmentId, Instant from, Instant to, Pageable pageable);

    long countByShipmentId(UUID shipmentId);

    /** Retention: drops fixes of finished trips received before the cut-off. */
    @Modifying
    @Query(value = """
            DELETE FROM shipment_tracking_locations l USING shipments s
             WHERE l.shipment_id = s.id AND l.received_at < :before
               AND s.status IN ('COMPLETED', 'CANCELLED', 'EXPIRED', 'DELIVERED')""", nativeQuery = true)
    int deleteFinishedBefore(@Param("before") Instant before);
}
