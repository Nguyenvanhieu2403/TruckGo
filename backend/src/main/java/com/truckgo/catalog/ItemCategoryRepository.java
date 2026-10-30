package com.truckgo.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItemCategoryRepository extends JpaRepository<ItemCategory, String> {
    List<ItemCategory> findByActiveTrueOrderBySortOrder();
}
