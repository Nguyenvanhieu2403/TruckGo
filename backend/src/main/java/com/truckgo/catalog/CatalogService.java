package com.truckgo.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.truckgo.catalog.dto.CatalogDtos.BodyTypeDto;
import com.truckgo.catalog.dto.CatalogDtos.ItemCategoryDto;
import com.truckgo.catalog.dto.CatalogDtos.ServiceDefinitionDto;
import com.truckgo.catalog.dto.CatalogDtos.VehicleTypeDto;
import com.truckgo.common.exception.ResourceNotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Single source of truth for order catalogs: served to the SPA and used by the backend to validate orders. */
@Service
public class CatalogService {
    public enum Catalog { ITEM_CATEGORIES, VEHICLE_TYPES, VEHICLE_BODY_TYPES, SHIPMENT_SERVICES }

    private final ItemCategoryRepository categories;
    private final CatalogVehicleTypeRepository vehicleTypes;
    private final VehicleBodyTypeRepository bodyTypes;
    private final ServiceDefinitionRepository services;
    private final CatalogCache cache;

    public CatalogService(ItemCategoryRepository categories, CatalogVehicleTypeRepository vehicleTypes, VehicleBodyTypeRepository bodyTypes,
                          ServiceDefinitionRepository services, CatalogCache cache) {
        this.categories = categories;
        this.vehicleTypes = vehicleTypes;
        this.bodyTypes = bodyTypes;
        this.services = services;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public List<ItemCategoryDto> itemCategories() {
        return cache.get("item-categories", new TypeReference<>() { },
                () -> categories.findByActiveTrueOrderBySortOrder().stream().map(ItemCategoryDto::from).toList());
    }

    @Transactional(readOnly = true)
    public List<VehicleTypeDto> vehicleTypes() {
        return cache.get("vehicle-types", new TypeReference<>() { },
                () -> vehicleTypes.findByActiveTrueOrderBySortOrder().stream().map(VehicleTypeDto::from).toList());
    }

    @Transactional(readOnly = true)
    public List<BodyTypeDto> bodyTypes() {
        return cache.get("vehicle-body-types", new TypeReference<>() { },
                () -> bodyTypes.findByActiveTrueOrderBySortOrder().stream().map(BodyTypeDto::from).toList());
    }

    @Transactional(readOnly = true)
    public List<ServiceDefinitionDto> shipmentServices() {
        return cache.get("shipment-services", new TypeReference<>() { },
                () -> services.findByActiveTrueOrderBySortOrder().stream().map(ServiceDefinitionDto::from).toList());
    }

    public Optional<ItemCategoryDto> itemCategory(String code) { return itemCategories().stream().filter(c -> c.code().equals(code)).findFirst(); }
    public Optional<VehicleTypeDto> vehicleType(String code) { return vehicleTypes().stream().filter(v -> v.code().equals(code)).findFirst(); }
    public Optional<BodyTypeDto> bodyType(String code) { return bodyTypes().stream().filter(b -> b.code().equals(code)).findFirst(); }
    public Optional<ServiceDefinitionDto> shipmentService(String code) { return shipmentServices().stream().filter(s -> s.code().equals(code)).findFirst(); }

    /** Activates/deactivates an entry and drops the cache once the change is committed. */
    @Transactional
    public void setActive(Catalog catalog, String code, boolean active) {
        CatalogEntry entry = repository(catalog).findById(code.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mục danh mục"));
        entry.setActive(active);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cache.evictAll(); }
            });
        } else {
            cache.evictAll();
        }
    }

    public void evictCache() { cache.evictAll(); }

    private JpaRepository<? extends CatalogEntry, String> repository(Catalog catalog) {
        return switch (catalog) {
            case ITEM_CATEGORIES -> categories;
            case VEHICLE_TYPES -> vehicleTypes;
            case VEHICLE_BODY_TYPES -> bodyTypes;
            case SHIPMENT_SERVICES -> services;
        };
    }
}
