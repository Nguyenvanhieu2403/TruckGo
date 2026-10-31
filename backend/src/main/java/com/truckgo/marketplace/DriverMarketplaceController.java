package com.truckgo.marketplace;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.ApplyRequest;
import com.truckgo.marketplace.dto.MarketplaceDtos.DriverApplicationResponse;
import com.truckgo.marketplace.dto.MarketplaceDtos.MarketplaceListItem;
import com.truckgo.marketplace.dto.MarketplaceDtos.MarketplaceShipmentDetail;
import com.truckgo.marketplace.dto.MarketplaceDtos.ReasonRequest;
import com.truckgo.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/driver")
@PreAuthorize("hasRole('DRIVER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Driver Marketplace", description = "Tài xế tìm đơn, gửi và quản lý yêu cầu nhận đơn")
public class DriverMarketplaceController {
    private final DriverMarketplaceService marketplace;

    public DriverMarketplaceController(DriverMarketplaceService marketplace) { this.marketplace = marketplace; }

    @GetMapping("/marketplace/shipments")
    @Operation(summary = "Danh sách đơn đang nhận tài xế", description = "sortBy: pickupTime (mặc định), price, distance, weight, publishedAt")
    public ApiResponse<PageResponse<MarketplaceListItem>> list(@AuthenticationPrincipal AuthenticatedUser me,
                                                               @RequestParam(required = false) String keyword,
                                                               @RequestParam(required = false) String pickupProvince,
                                                               @RequestParam(required = false) String pickupDistrict,
                                                               @RequestParam(required = false) String deliveryProvince,
                                                               @RequestParam(required = false) String deliveryDistrict,
                                                               @RequestParam(required = false) String vehicleTypeId,
                                                               @RequestParam(required = false) BigDecimal minPrice,
                                                               @RequestParam(required = false) BigDecimal maxPrice,
                                                               @RequestParam(required = false) BigDecimal minWeight,
                                                               @RequestParam(required = false) BigDecimal maxWeight,
                                                               @RequestParam(required = false) BigDecimal maxDistance,
                                                               @RequestParam(required = false) String sortBy,
                                                               @RequestParam(required = false) String sortDirection,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        MarketplaceFilter filter = new MarketplaceFilter(keyword, pickupProvince, pickupDistrict, deliveryProvince, deliveryDistrict, vehicleTypeId,
                minPrice, maxPrice, minWeight, maxWeight, maxDistance, sortBy, sortDirection, page, size);
        filter.sort();
        filter.ascending();
        return ApiResponse.success("Request completed successfully", marketplace.list(me.id(), filter), request.getRequestURI());
    }

    @GetMapping("/marketplace/shipments/{shipmentId}")
    @Operation(summary = "Chi tiết đơn (địa chỉ chính xác chỉ hiện khi đơn đã giao cho bạn)")
    public ApiResponse<MarketplaceShipmentDetail> detail(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", marketplace.detail(me.id(), shipmentId), request.getRequestURI());
    }

    @PostMapping("/marketplace/shipments/{shipmentId}/applications")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Gửi yêu cầu nhận đơn với một xe của bạn")
    public ApiResponse<DriverApplicationResponse> apply(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                                        @Valid @RequestBody ApplyRequest body, HttpServletRequest request) {
        return ApiResponse.success("Application submitted", marketplace.apply(me.id(), shipmentId, body), request.getRequestURI());
    }

    @GetMapping("/applications/my")
    @Operation(summary = "Yêu cầu nhận đơn của tôi")
    public ApiResponse<PageResponse<DriverApplicationResponse>> mine(@AuthenticationPrincipal AuthenticatedUser me, @RequestParam(required = false) ApplicationStatus status,
                                                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                                                     HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", marketplace.myApplications(me.id(), status, page, size), request.getRequestURI());
    }

    @GetMapping("/applications/{applicationId}")
    @Operation(summary = "Chi tiết yêu cầu nhận đơn của tôi")
    public ApiResponse<DriverApplicationResponse> mineOne(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID applicationId, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", marketplace.myApplication(me.id(), applicationId), request.getRequestURI());
    }

    @PostMapping("/applications/{applicationId}/withdraw")
    @Operation(summary = "Rút yêu cầu đang chờ")
    public ApiResponse<DriverApplicationResponse> withdraw(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID applicationId,
                                                           @Valid @RequestBody(required = false) ReasonRequest body, HttpServletRequest request) {
        return ApiResponse.success("Application withdrawn", marketplace.withdraw(me.id(), applicationId, body == null ? null : body.reason()), request.getRequestURI());
    }
}
