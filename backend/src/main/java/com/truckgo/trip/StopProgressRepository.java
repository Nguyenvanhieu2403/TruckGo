package com.truckgo.trip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StopProgressRepository extends JpaRepository<StopProgress, UUID> {
    List<StopProgress> findByShipmentIdOrderBySequenceNoAsc(UUID shipmentId);
}
