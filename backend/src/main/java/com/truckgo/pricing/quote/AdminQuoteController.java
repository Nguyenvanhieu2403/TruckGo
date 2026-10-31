package com.truckgo.pricing.quote;

import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.pricing.dto.QuoteDtos.AdminQuoteResponse;
import com.truckgo.pricing.dto.QuoteDtos.ManualAdjustmentRequest;
import com.truckgo.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/quotes")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin pricing", description = "Báo giá cần xử lý thủ công")
public class AdminQuoteController {
    private final QuoteService quotes;

    public AdminQuoteController(QuoteService quotes) { this.quotes = quotes; }

    @GetMapping("/pending")
    @Operation(summary = "Báo giá chờ quản trị viên xử lý (cũ nhất trước)")
    public ApiResponse<PageResponse<AdminQuoteResponse>> pending(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("createdAt").ascending());
        return ApiResponse.success("Request completed successfully", quotes.pendingReview(pageable), request.getRequestURI());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết báo giá (kèm snapshot tính giá)")
    public ApiResponse<AdminQuoteResponse> get(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", quotes.adminGet(id), request.getRequestURI());
    }

    @PostMapping("/{id}/manual-adjustment")
    @Operation(summary = "Điều chỉnh thủ công", description = "Nhập quãng đường đã xác minh và/hoặc khoản điều chỉnh (có thể âm) kèm lý do; tạo phiên bản báo giá mới.")
    public ApiResponse<AdminQuoteResponse> adjust(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, @Valid @RequestBody ManualAdjustmentRequest body,
                                                  HttpServletRequest request) {
        if (body.distanceKm() == null && body.adjustmentAmount() == null) throw FieldErrorException.invalid("distanceKm", "Nhập quãng đường hoặc khoản điều chỉnh");
        return ApiResponse.success("Quote adjusted", quotes.manualAdjustment(me.id(), id, body.distanceKm(), body.adjustmentAmount(), body.reason()), request.getRequestURI());
    }

    @PostMapping("/{id}/recalculate")
    @Operation(summary = "Tính lại báo giá", description = "Dùng bảng giá hiện hành; giữ quãng đường đã xác minh nếu có.")
    public ApiResponse<AdminQuoteResponse> recalculate(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Quote recalculated", quotes.recalculate(me.id(), id), request.getRequestURI());
    }
}
