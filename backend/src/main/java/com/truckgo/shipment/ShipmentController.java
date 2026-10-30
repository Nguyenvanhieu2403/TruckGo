package com.truckgo.shipment;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.shipment.dto.ShipmentRequests.CancelRequest;
import com.truckgo.shipment.dto.ShipmentRequests.DraftRequest;
import com.truckgo.shipment.dto.ShipmentResponses.ShipmentDetail;
import com.truckgo.shipment.dto.ShipmentResponses.ShipmentSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shipments")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Shipment", description = "Đơn vận chuyển của khách hàng")
public class ShipmentController {
    private final ShipmentService shipments;

    public ShipmentController(ShipmentService shipments) { this.shipments = shipments; }

    @PostMapping("/draft")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Tạo đơn nháp", description = "Tổng trọng lượng/thể tích do máy chủ tính; chưa tính giá cước.")
    public ApiResponse<ShipmentDetail> createDraft(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody DraftRequest body, HttpServletRequest request) {
        return ApiResponse.success("Shipment draft created", shipments.createDraft(me.id(), body), request.getRequestURI());
    }

    @PutMapping("/{id}/draft")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Cập nhật đơn nháp", description = "Thay toàn bộ điểm dừng, hàng hóa, dịch vụ; ảnh đính kèm giữ nguyên. Cần gửi version.")
    public ApiResponse<ShipmentDetail> updateDraft(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, @Valid @RequestBody DraftRequest body, HttpServletRequest request) {
        return ApiResponse.success("Shipment draft updated", shipments.updateDraft(me.id(), id, body), request.getRequestURI());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Đăng đơn", description = "DRAFT → PUBLISHED. Không tự gán tài xế, không tạo giá.")
    public ApiResponse<ShipmentDetail> publish(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Shipment published", shipments.publish(me.id(), id), request.getRequestURI());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Hủy đơn", description = "Chỉ khi đơn ở DRAFT hoặc PUBLISHED (chưa có tài xế nhận).")
    public ApiResponse<ShipmentDetail> cancel(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, @Valid @RequestBody CancelRequest body, HttpServletRequest request) {
        return ApiResponse.success("Shipment cancelled", shipments.cancel(me.id(), id, body.reason()), request.getRequestURI());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Xóa đơn nháp", description = "Xóa mềm; đơn đã đăng phải dùng hủy.")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        shipments.deleteDraft(me.id(), id);
        return ApiResponse.success("Shipment draft deleted", null, request.getRequestURI());
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Đơn của tôi", description = "Lọc theo trạng thái, từ khóa (mã đơn, địa chỉ, người liên hệ), ngày tạo; sortBy: createdAt, pickupTime, shipmentCode, totalWeightKg, status.")
    public ApiResponse<PageResponse<ShipmentSummary>> mine(@AuthenticationPrincipal AuthenticatedUser me,
                                                           @RequestParam(required = false) ShipmentStatus status,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size,
                                                           @RequestParam(defaultValue = "createdAt") String sortBy, @RequestParam(defaultValue = "desc") String sortDirection,
                                                           HttpServletRequest request) {
        ShipmentFilter filter = new ShipmentFilter(status, keyword, fromDate, toDate, page, size, sortBy, sortDirection);
        return ApiResponse.success("Request completed successfully", shipments.listMine(me.id(), filter), request.getRequestURI());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @Operation(summary = "Chi tiết đơn", description = "Chủ đơn hoặc quản trị viên.")
    public ApiResponse<ShipmentDetail> get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", shipments.get(me, id), request.getRequestURI());
    }
}
