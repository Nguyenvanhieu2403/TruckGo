package com.truckgo.pricing.config;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PricingConfigRepository extends JpaRepository<PricingConfig, UUID> {
    @Query("select c from PricingConfig c where (:vehicle is null or c.vehicleTypeCode = :vehicle) and (:active is null or c.active = :active)")
    Page<PricingConfig> search(@Param("vehicle") String vehicleTypeCode, @Param("active") Boolean active, Pageable pageable);

    List<PricingConfig> findByVehicleTypeCodeAndActiveTrue(String vehicleTypeCode);

    List<PricingConfig> findByActiveTrue();
}
