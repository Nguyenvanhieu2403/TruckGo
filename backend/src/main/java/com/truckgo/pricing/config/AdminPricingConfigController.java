package com.truckgo.pricing.config;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.pricing.dto.PricingConfigDtos.ConfigRequest;
import com.truckgo.pricing.dto.PricingConfigDtos.ConfigResponse;
import com.truckgo.pricing.dto.PricingConfigDtos.HistoryResponse;
import com.truckgo.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/pricing-configs")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin pricing", description = "Bảng giá theo loại xe")
public class AdminPricingConfigController {
    private final PricingConfigService pricing;

    public AdminPricingConfigController(PricingConfigService pricing) { this.pricing = pricing; }

    @GetMapping
    @Operation(summary = "Danh sách bảng giá", description = "Lọc theo loại xe và trạng thái bật/tắt.")
    public ApiResponse<PageResponse<ConfigResponse>> list(@RequestParam(required = false) String vehicleTypeCode, @RequestParam(required = false) Boolean active,
                                                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("vehicleTypeCode").and(Sort.by(Sort.Direction.DESC, "effectiveFrom")));
        return ok(pricing.search(vehicleTypeCode, active, pageable), request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết bảng giá (kèm khung giờ, phụ phí khác, phí dịch vụ)")
    public ApiResponse<ConfigResponse> get(@PathVariable UUID id, HttpServletRequest request) { return ok(pricing.get(id), request); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo bảng giá", description = "Không được trùng thời gian hiệu lực với bảng giá đang bật của cùng loại xe.")
    public ApiResponse<ConfigResponse> create(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody ConfigRequest body, HttpServletRequest request) {
        return ApiResponse.success("Pricing config created", pricing.create(me.id(), body), request.getRequestURI());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật bảng giá", description = "Cần version; báo giá đã tạo không bị ảnh hưởng (có snapshot).")
    public ApiResponse<ConfigResponse> update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, @Valid @RequestBody ConfigRequest body, HttpServletRequest request) {
        return ApiResponse.success("Pricing config updated", pricing.update(me.id(), id, body), request.getRequestURI());
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "Bật bảng giá")
    public ApiResponse<ConfigResponse> activate(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Pricing config activated", pricing.setActive(me.id(), id, true), request.getRequestURI());
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Tắt bảng giá")
    public ApiResponse<ConfigResponse> deactivate(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Pricing config deactivated", pricing.setActive(me.id(), id, false), request.getRequestURI());
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Lịch sử thay đổi bảng giá")
    public ApiResponse<List<HistoryResponse>> history(@PathVariable UUID id, HttpServletRequest request) { return ok(pricing.history(id), request); }

    private static <T> ApiResponse<T> ok(T data, HttpServletRequest request) { return ApiResponse.success("Request completed successfully", data, request.getRequestURI()); }
}
