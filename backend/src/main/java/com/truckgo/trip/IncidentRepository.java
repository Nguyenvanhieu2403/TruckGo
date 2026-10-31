package com.truckgo.trip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {
    List<Incident> findByShipmentIdOrderByCreatedAtDesc(UUID shipmentId);
    long countByShipmentIdAndStatusIn(UUID shipmentId, java.util.Collection<IncidentStatus> statuses);
}
