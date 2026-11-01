package com.truckgo.marketplace;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminApplicationResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminAssignmentResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AdminMarketplaceShipment;
import com.truckgo.marketplace.dto.MarketplaceDtos.AssignmentOverview;
import com.truckgo.marketplace.dto.MarketplaceDtos.ReleaseRequest;
import com.truckgo.marketplace.dto.MarketplaceDtos.ReplaceRequest;
import com.truckgo.marketplace.dto.MarketplaceDtos.ScheduleEntry;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.shipment.ShipmentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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
                                                                        @RequestParam(defaultValue = "false") boolean understaffed,
                                                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                                                        HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", service.shipments(status, keyword, understaffed, page, size), request.getRequestURI());
    }

    @GetMapping("/shipments/{shipmentId}/assignments")
    @Operation(summary = "Phân công và lịch sử phân công của một đơn")
    public ApiResponse<AssignmentOverview> shipmentAssignments(@PathVariable UUID shipmentId, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", service.shipmentAssignments(shipmentId), request.getRequestURI());
    }

    @PostMapping("/shipments/{shipmentId}/assignments/{assignmentId}/release")
    @Operation(summary = "Gỡ tài xế khỏi đơn (trước khi chuyến bắt đầu, cần lý do)")
    public ApiResponse<AssignmentOverview> release(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID assignmentId,
                                                   @Valid @RequestBody ReleaseRequest body, HttpServletRequest request) {
        return ApiResponse.success("Driver removed", service.release(me.id(), shipmentId, assignmentId, body.reason()), request.getRequestURI());
    }

    @PostMapping("/shipments/{shipmentId}/assignments/{assignmentId}/replace")
    @Operation(summary = "Thay tài xế bằng một yêu cầu đang chờ")
    public ApiResponse<AssignmentOverview> replace(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID assignmentId,
                                                   @Valid @RequestBody ReplaceRequest body, HttpServletRequest request) {
        return ApiResponse.success("Driver replaced", service.replace(me.id(), shipmentId, assignmentId, body.applicationId(), body.reason()), request.getRequestURI());
    }

    @GetMapping("/schedules")
    @Operation(summary = "Lịch bận của tài xế và phương tiện (phân công đang hiệu lực)")
    public ApiResponse<PageResponse<ScheduleEntry>> schedules(@RequestParam(required = false) UUID driverId, @RequestParam(required = false) UUID vehicleId,
                                                             @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
                                                             HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", service.schedules(driverId, vehicleId, from, to, page, size), request.getRequestURI());
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
