package com.truckgo.shipment;

import com.truckgo.catalog.CatalogService;
import com.truckgo.catalog.ServiceValueType;
import com.truckgo.catalog.dto.CatalogDtos.BodyTypeDto;
import com.truckgo.catalog.dto.CatalogDtos.ItemCategoryDto;
import com.truckgo.catalog.dto.CatalogDtos.ServiceDefinitionDto;
import com.truckgo.catalog.dto.CatalogDtos.ServiceOptionDto;
import com.truckgo.catalog.dto.CatalogDtos.VehicleTypeDto;
import com.truckgo.shipment.dto.ShipmentRequests.DraftRequest;
import com.truckgo.shipment.dto.ShipmentRequests.ItemRequest;
import com.truckgo.shipment.dto.ShipmentRequests.ServiceRequest;
import com.truckgo.shipment.dto.ShipmentRequests.StopRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

final class ShipmentTestData {
    private ShipmentTestData() { }

    static CatalogService catalogs() {
        CatalogService catalogs = mock(CatalogService.class);
        List<ServiceDefinitionDto> services = List.of(
                new ServiceDefinitionDto("FRAGILE_GOODS", "Hàng dễ vỡ", "GOODS", ServiceValueType.BOOLEAN, null, null, null, null, List.of()),
                new ServiceDefinitionDto("ELEVATOR_AVAILABLE", "Có thang máy", "SITE", ServiceValueType.BOOLEAN, null, "ELEVATOR", null, null, List.of()),
                new ServiceDefinitionDto("ELEVATOR_UNAVAILABLE", "Không có thang máy", "SITE", ServiceValueType.BOOLEAN, null, "ELEVATOR", null, null, List.of()),
                new ServiceDefinitionDto("UPSTAIRS_DELIVERY", "Vận chuyển lên tầng", "SITE", ServiceValueType.NUMBER, null, null, 1, 100, List.of()),
                new ServiceDefinitionDto("SPECIAL_REQUIREMENT", "Yêu cầu đặc biệt", "OTHER", ServiceValueType.TEXT, null, null, null, null, List.of()),
                new ServiceDefinitionDto("LOADING_SUPPORT", "Hỗ trợ bốc hàng", "LOADING", ServiceValueType.OPTION, null, null, null, null,
                        List.of(new ServiceOptionDto("ONE_PERSON", "Cần một người hỗ trợ"), new ServiceOptionDto("TEAM", "Cần đội bốc xếp"))));
        lenient().when(catalogs.itemCategory(anyString())).thenAnswer(inv -> List.of("ELECTRONICS", "FURNITURE", "OTHER").contains(inv.<String>getArgument(0))
                ? Optional.of(new ItemCategoryDto(inv.getArgument(0), inv.getArgument(0), null)) : Optional.empty());
        lenient().when(catalogs.vehicleType(anyString())).thenAnswer(inv -> "TRUCK_500KG".equals(inv.getArgument(0))
                ? Optional.of(new VehicleTypeDto("TRUCK_500KG", "Xe tải 500 kg", new BigDecimal("500"), new BigDecimal("200"), new BigDecimal("130"), new BigDecimal("130")))
                : Optional.empty());
        lenient().when(catalogs.bodyType(anyString())).thenAnswer(inv -> "CLOSED_BOX".equals(inv.getArgument(0)) ? Optional.of(new BodyTypeDto("CLOSED_BOX", "Thùng kín")) : Optional.empty());
        lenient().when(catalogs.shipmentService(anyString())).thenAnswer(inv -> services.stream().filter(s -> s.code().equals(inv.getArgument(0))).findFirst());
        return catalogs;
    }

    static StopRequest pickup() {
        return new StopRequest(StopType.PICKUP, 1, "Nguyen Van A", "0912345678", "Hà Nội", "Nam Từ Liêm", "Mỹ Đình", "Số 10, đường X",
                new BigDecimal("21.0285"), new BigDecimal("105.8542"), null);
    }

    static StopRequest delivery() {
        return new StopRequest(StopType.DELIVERY, 2, "Nguyen Van B", "0987654321", "Hà Nội", "Cầu Giấy", "Dịch Vọng", "Số 20, đường Y", null, null, "Gọi trước");
    }

    static ItemRequest boxes() {
        return new ItemRequest("Thùng hàng điện tử", "ELECTRONICS", "Cần bảo vệ", 10, new BigDecimal("20"), new BigDecimal("50"), new BigDecimal("40"), new BigDecimal("30"));
    }

    static DraftRequest draft(List<StopRequest> stops, List<ItemRequest> items, List<ServiceRequest> services) {
        return new DraftRequest(OffsetDateTime.parse("2027-01-05T08:00:00+07:00"), OffsetDateTime.parse("2027-01-05T18:00:00+07:00"), null, null, 1,
                null, null, null, null, "Gọi trước khi đến", stops, items, services, null);
    }

    static DraftRequest validDraft() { return draft(List.of(pickup(), delivery()), List.of(boxes()), List.of(new ServiceRequest("FRAGILE_GOODS", "true"))); }
}
