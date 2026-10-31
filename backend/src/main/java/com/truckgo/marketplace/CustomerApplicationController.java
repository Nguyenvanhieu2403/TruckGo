package com.truckgo.marketplace;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.ApplicantResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.ReasonRequest;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customer/shipments/{shipmentId}/applications")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer Applicants", description = "Khách hàng xem và chọn tài xế cho đơn của mình")
public class CustomerApplicationController {
    private final CustomerApplicationService service;

    public CustomerApplicationController(CustomerApplicationService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "Tài xế đã gửi yêu cầu nhận đơn")
    public ApiResponse<List<ApplicantResponse>> list(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", service.list(me.id(), shipmentId), request.getRequestURI());
    }

    @GetMapping("/{applicationId}")
    @Operation(summary = "Chi tiết một yêu cầu")
    public ApiResponse<ApplicantResponse> get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID applicationId,
                                              HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", service.get(me.id(), shipmentId, applicationId), request.getRequestURI());
    }

    @PostMapping("/{applicationId}/accept")
    @Operation(summary = "Chọn tài xế (đơn chuyển sang ASSIGNED, các yêu cầu khác bị từ chối)")
    public ApiResponse<ApplicantResponse> accept(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID applicationId,
                                                 HttpServletRequest request) {
        return ApiResponse.success("Driver assigned", service.accept(me.id(), shipmentId, applicationId), request.getRequestURI());
    }

    @PostMapping("/{applicationId}/reject")
    @Operation(summary = "Từ chối một yêu cầu")
    public ApiResponse<ApplicantResponse> reject(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID applicationId,
                                                 @Valid @RequestBody(required = false) ReasonRequest body, HttpServletRequest request) {
        return ApiResponse.success("Application rejected", service.reject(me.id(), shipmentId, applicationId, body == null ? null : body.reason()), request.getRequestURI());
    }
}
