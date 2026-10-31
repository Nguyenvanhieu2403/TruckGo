package com.truckgo.pricing.quote;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.pricing.dto.QuoteDtos.QuoteResponse;
import com.truckgo.pricing.dto.QuoteDtos.RejectRequest;
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

/** Customer pricing of an order. No request carries a price: every amount is computed and stored by the server. */
@RestController
@RequestMapping("/api/v1/shipments/{shipmentId}/pricing")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Pricing", description = "Báo giá đơn vận chuyển")
public class QuoteController {
    private final QuoteService quotes;

    public QuoteController(QuoteService quotes) { this.quotes = quotes; }

    @PostMapping("/preview")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Tính giá", description = "Tạo phiên bản báo giá mới từ dữ liệu đơn đã lưu; phiên bản trước bị thay thế.")
    public ApiResponse<QuoteResponse> preview(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ApiResponse.success("Quote created", quotes.preview(me.id(), shipmentId), request.getRequestURI());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @Operation(summary = "Báo giá mới nhất", description = "data = null khi đơn chưa có báo giá.")
    public ApiResponse<QuoteResponse> current(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", quotes.getCurrent(me, shipmentId).orElse(null), request.getRequestURI());
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @Operation(summary = "Lịch sử báo giá (mới nhất trước)")
    public ApiResponse<List<QuoteResponse>> history(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", quotes.history(me, shipmentId), request.getRequestURI());
    }

    @PostMapping("/accept")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Xác nhận báo giá", description = "Chỉ báo giá hiện hành CALCULATED còn hiệu lực.")
    public ApiResponse<QuoteResponse> accept(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ApiResponse.success("Quote accepted", quotes.accept(me.id(), shipmentId), request.getRequestURI());
    }

    @PostMapping("/reject")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Từ chối báo giá")
    public ApiResponse<QuoteResponse> reject(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                             @Valid @RequestBody(required = false) RejectRequest body, HttpServletRequest request) {
        return ApiResponse.success("Quote rejected", quotes.reject(me.id(), shipmentId, body == null ? null : body.reason()), request.getRequestURI());
    }
}
