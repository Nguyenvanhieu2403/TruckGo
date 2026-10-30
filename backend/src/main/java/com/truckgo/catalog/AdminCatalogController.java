package com.truckgo.catalog;

import com.truckgo.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/v1/admin/catalogs")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin", description = "Quản lý danh mục")
public class AdminCatalogController {
    public record ActivationRequest(@NotNull(message = "Vui lòng chọn trạng thái") Boolean active) { }

    private final CatalogService catalogs;

    public AdminCatalogController(CatalogService catalogs) { this.catalogs = catalogs; }

    @PatchMapping("/{catalog}/{code}")
    @Operation(summary = "Bật/tắt một mục danh mục", description = "catalog: item-categories, vehicle-types, vehicle-body-types, shipment-services. Cache được xóa sau khi lưu.")
    public ApiResponse<Void> setActive(@PathVariable String catalog, @PathVariable String code, @Valid @RequestBody ActivationRequest body, HttpServletRequest request) {
        catalogs.setActive(parse(catalog), code, body.active());
        return ApiResponse.success("Catalog updated", null, request.getRequestURI());
    }

    @PostMapping("/cache/evict")
    @Operation(summary = "Xóa cache danh mục", description = "Dùng sau khi chỉnh danh mục trực tiếp trong database.")
    public ApiResponse<Void> evict(HttpServletRequest request) {
        catalogs.evictCache();
        return ApiResponse.success("Catalog cache cleared", null, request.getRequestURI());
    }

    private static CatalogService.Catalog parse(String value) {
        try { return CatalogService.Catalog.valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Danh mục không hợp lệ"); }
    }
}
