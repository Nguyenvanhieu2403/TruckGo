package com.truckgo.catalog.dto;

import com.truckgo.catalog.CatalogVehicleType;
import com.truckgo.catalog.ItemCategory;
import com.truckgo.catalog.ServiceDefinition;
import com.truckgo.catalog.ServiceValueType;
import com.truckgo.catalog.VehicleBodyType;

import java.math.BigDecimal;
import java.util.List;

/** Read models served to clients and stored in the Redis cache. */
public final class CatalogDtos {
    private CatalogDtos() { }

    public record ItemCategoryDto(String code, String name, String description) {
        public static ItemCategoryDto from(ItemCategory c) { return new ItemCategoryDto(c.getCode(), c.getName(), c.getDescription()); }
    }

    public record VehicleTypeDto(String code, String name, BigDecimal maxPayloadKg, BigDecimal cargoLengthCm, BigDecimal cargoWidthCm, BigDecimal cargoHeightCm) {
        public static VehicleTypeDto from(CatalogVehicleType v) {
            return new VehicleTypeDto(v.getCode(), v.getName(), v.getMaxPayloadKg(), v.getCargoLengthCm(), v.getCargoWidthCm(), v.getCargoHeightCm());
        }
    }

    public record BodyTypeDto(String code, String name) {
        public static BodyTypeDto from(VehicleBodyType b) { return new BodyTypeDto(b.getCode(), b.getName()); }
    }

    public record ServiceOptionDto(String value, String label) { }

    public record ServiceDefinitionDto(String code, String name, String group, ServiceValueType valueType, String description,
                                       String exclusiveGroup, Integer minValue, Integer maxValue, List<ServiceOptionDto> options) {
        public static ServiceDefinitionDto from(ServiceDefinition s) {
            return new ServiceDefinitionDto(s.getCode(), s.getName(), s.getGroupCode(), s.getValueType(), s.getDescription(), s.getExclusiveGroup(),
                    s.getMinValue(), s.getMaxValue(), s.getOptions().stream().map(o -> new ServiceOptionDto(o.getValue(), o.getLabel())).toList());
        }
    }
}
