package com.truckgo.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VehicleBodyTypeRepository extends JpaRepository<VehicleBodyType, String> {
    List<VehicleBodyType> findByActiveTrueOrderBySortOrder();
}
