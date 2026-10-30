package com.truckgo.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceDefinitionRepository extends JpaRepository<ServiceDefinition, String> {
    List<ServiceDefinition> findByActiveTrueOrderBySortOrder();
}
