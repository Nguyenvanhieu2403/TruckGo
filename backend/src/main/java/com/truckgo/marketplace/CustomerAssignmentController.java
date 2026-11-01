package com.truckgo.marketplace;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.AssignmentOverview;
import com.truckgo.marketplace.dto.MarketplaceDtos.ReleaseRequest;
import com.truckgo.marketplace.dto.MarketplaceDtos.ReplaceRequest;
import com.truckgo.security.AuthenticatedUser;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customer/shipments/{shipmentId}/assignments")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer Assignments", description = "Tiến độ phân công, lịch sử, gỡ hoặc thay tài xế trước khi chuyến bắt đầu")
public class CustomerAssignmentController {
    private final CustomerApplicationService service;

    public CustomerAssignmentController(CustomerApplicationService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "Số xe cần, đã phân công, còn thiếu; danh sách tài xế và lịch sử phân công")
    public ApiResponse<AssignmentOverview> overview(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", service.assignments(me.id(), shipmentId), request.getRequestURI());
    }

    @PostMapping("/{assignmentId}/release")
    @Operation(summary = "Gỡ tài xế (chỉ trước khi chuyến bắt đầu, cần lý do)")
    public ApiResponse<AssignmentOverview> release(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID assignmentId,
                                                   @Valid @RequestBody ReleaseRequest body, HttpServletRequest request) {
        return ApiResponse.success("Driver removed", service.release(me.id(), shipmentId, assignmentId, body.reason()), request.getRequestURI());
    }

    @PostMapping("/{assignmentId}/replace")
    @Operation(summary = "Thay tài xế bằng một tài xế đang chờ duyệt yêu cầu")
    public ApiResponse<AssignmentOverview> replace(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID assignmentId,
                                                   @Valid @RequestBody ReplaceRequest body, HttpServletRequest request) {
        return ApiResponse.success("Driver replaced", service.replace(me.id(), shipmentId, assignmentId, body.applicationId(), body.reason()), request.getRequestURI());
    }
}
