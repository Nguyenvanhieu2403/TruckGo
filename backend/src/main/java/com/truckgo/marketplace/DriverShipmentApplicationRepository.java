package com.truckgo.marketplace;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverShipmentApplicationRepository extends JpaRepository<DriverShipmentApplication, UUID>, JpaSpecificationExecutor<DriverShipmentApplication> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DriverShipmentApplication a where a.id = :id")
    Optional<DriverShipmentApplication> findByIdForUpdate(@Param("id") UUID id);

    List<DriverShipmentApplication> findByShipmentIdAndStatus(UUID shipmentId, ApplicationStatus status);

    List<DriverShipmentApplication> findByShipmentIdOrderByAppliedAtAsc(UUID shipmentId);

    Optional<DriverShipmentApplication> findFirstByShipmentIdAndStatus(UUID shipmentId, ApplicationStatus status);

    Page<DriverShipmentApplication> findByDriverId(UUID driverId, Pageable pageable);

    Page<DriverShipmentApplication> findByDriverIdAndStatus(UUID driverId, ApplicationStatus status, Pageable pageable);

    List<DriverShipmentApplication> findByDriverIdAndShipmentIdIn(UUID driverId, Collection<UUID> shipmentIds);

    List<DriverShipmentApplication> findByDriverIdAndShipmentIdOrderByAppliedAtDesc(UUID driverId, UUID shipmentId);

    long countByDriverIdAndStatus(UUID driverId, ApplicationStatus status);

    long countByShipmentIdAndStatus(UUID shipmentId, ApplicationStatus status);

    long countByShipmentId(UUID shipmentId);
}
