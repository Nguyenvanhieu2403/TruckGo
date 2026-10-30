package com.truckgo.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogVehicleTypeRepository extends JpaRepository<CatalogVehicleType, String> {
    List<CatalogVehicleType> findByActiveTrueOrderBySortOrder();
}
