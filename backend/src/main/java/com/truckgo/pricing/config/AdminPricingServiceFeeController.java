package com.truckgo.pricing.config;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.pricing.dto.PricingConfigDtos.ServiceFeeRequest;
import com.truckgo.pricing.dto.PricingConfigDtos.ServiceFeeResponse;
import com.truckgo.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/admin/pricing-service-fees")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin pricing", description = "Phí dịch vụ bổ sung theo bảng giá")
public class AdminPricingServiceFeeController {
    private final PricingConfigService pricing;

    public AdminPricingServiceFeeController(PricingConfigService pricing) { this.pricing = pricing; }

    @GetMapping
    @Operation(summary = "Phí dịch vụ của một bảng giá")
    public ApiResponse<List<ServiceFeeResponse>> list(@RequestParam UUID pricingConfigId, @RequestParam(defaultValue = "false") boolean includeInactive, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", pricing.serviceFees(pricingConfigId, includeInactive), request.getRequestURI());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Thêm phí dịch vụ", description = "Dịch vụ lấy từ danh mục Sprint 3; FIXED, PERCENTAGE, PER_UNIT, PER_STOP hoặc FREE.")
    public ApiResponse<ServiceFeeResponse> create(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody ServiceFeeRequest body, HttpServletRequest request) {
        return ApiResponse.success("Service fee created", pricing.createServiceFee(me.id(), body), request.getRequestURI());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật phí dịch vụ (cần version)")
    public ApiResponse<ServiceFeeResponse> update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, @Valid @RequestBody ServiceFeeRequest body, HttpServletRequest request) {
        return ApiResponse.success("Service fee updated", pricing.updateServiceFee(me.id(), id, body), request.getRequestURI());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Xóa phí dịch vụ", description = "Xóa mềm; lịch sử được giữ lại.")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        pricing.deleteServiceFee(me.id(), id);
        return ApiResponse.success("Service fee deleted", null, request.getRequestURI());
    }
}
