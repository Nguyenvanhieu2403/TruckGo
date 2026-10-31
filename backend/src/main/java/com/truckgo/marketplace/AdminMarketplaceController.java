package com.truckgo.marketplace;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminApplicationResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminAssignmentResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminMarketplaceShipment;
import com.truckgo.shipment.ShipmentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/marketplace")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin Marketplace", description = "Theo dõi đơn, yêu cầu nhận đơn và lịch sử phân công")
public class AdminMarketplaceController {
    private final AdminMarketplaceService service;

    public AdminMarketplaceController(AdminMarketplaceService service) { this.service = service; }

    @GetMapping("/shipments")
    @Operation(summary = "Đơn trên Marketplace kèm số yêu cầu và tài xế được chọn")
    public ApiResponse<PageResponse<AdminMarketplaceShipment>> shipments(@RequestParam(required = false) ShipmentStatus status, @RequestParam(required = false) String keyword,
                                                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                                                        HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", service.shipments(status, keyword, page, size), request.getRequestURI());
    }

    @GetMapping("/applications")
    @Operation(summary = "Tất cả yêu cầu nhận đơn")
    public ApiResponse<PageResponse<AdminApplicationResponse>> applications(@RequestParam(required = false) ApplicationStatus status,
                                                                           @RequestParam(required = false) UUID shipmentId, @RequestParam(required = false) UUID driverId,
                                                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                                                           HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", service.applications(status, shipmentId, driverId, page, size), request.getRequestURI());
    }

    @GetMapping("/applications/{id}")
    @Operation(summary = "Chi tiết yêu cầu nhận đơn")
    public ApiResponse<AdminApplicationResponse> application(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", service.application(id), request.getRequestURI());
    }

    @GetMapping("/assignments")
    @Operation(summary = "Lịch sử phân công tài xế")
    public ApiResponse<PageResponse<AdminAssignmentResponse>> assignments(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                                                         HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", service.assignments(page, size), request.getRequestURI());
    }
}
