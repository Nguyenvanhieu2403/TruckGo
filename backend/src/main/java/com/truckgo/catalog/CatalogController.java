package com.truckgo.catalog;

import com.truckgo.catalog.dto.CatalogDtos.BodyTypeDto;
import com.truckgo.catalog.dto.CatalogDtos.ItemCategoryDto;
import com.truckgo.catalog.dto.CatalogDtos.ServiceDefinitionDto;
import com.truckgo.catalog.dto.CatalogDtos.VehicleTypeDto;
import com.truckgo.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalogs")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Catalog", description = "Danh mục dùng cho tạo đơn vận chuyển")
public class CatalogController {
    private final CatalogService catalogs;

    public CatalogController(CatalogService catalogs) { this.catalogs = catalogs; }

    @GetMapping("/item-categories")
    @Operation(summary = "Loại hàng hóa")
    public ApiResponse<List<ItemCategoryDto>> itemCategories(HttpServletRequest request) { return ok(catalogs.itemCategories(), request); }

    @GetMapping("/vehicle-types")
    @Operation(summary = "Loại xe theo tải trọng danh nghĩa")
    public ApiResponse<List<VehicleTypeDto>> vehicleTypes(HttpServletRequest request) { return ok(catalogs.vehicleTypes(), request); }

    @GetMapping("/vehicle-body-types")
    @Operation(summary = "Loại thùng xe")
    public ApiResponse<List<BodyTypeDto>> bodyTypes(HttpServletRequest request) { return ok(catalogs.bodyTypes(), request); }

    @GetMapping("/shipment-services")
    @Operation(summary = "Dịch vụ bổ sung")
    public ApiResponse<List<ServiceDefinitionDto>> services(HttpServletRequest request) { return ok(catalogs.shipmentServices(), request); }

    private static <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", data, request.getRequestURI());
    }
}
