package com.truckgo.vehicle;

import com.truckgo.common.domain.VerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    List<Vehicle> findByDriverIdOrderByCreatedAtDesc(UUID driverId);
    Optional<Vehicle> findByIdAndDriverId(UUID id, UUID driverId);
    boolean existsByPlateNumber(String plateNumber);
    boolean existsByPlateNumberAndIdNot(String plateNumber, UUID id);
    Page<Vehicle> findByVerificationStatus(VerificationStatus status, Pageable pageable);
}
