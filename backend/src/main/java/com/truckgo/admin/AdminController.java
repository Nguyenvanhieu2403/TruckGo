package com.truckgo.admin;

import com.truckgo.admin.dto.AdminDriverResponse;
import com.truckgo.admin.dto.AdminVehicleResponse;
import com.truckgo.admin.dto.ChangeUserStatusRequest;
import com.truckgo.admin.dto.RejectRequest;
import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.user.UserRole;
import com.truckgo.user.UserStatus;
import com.truckgo.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin", description = "Duyệt hồ sơ tài xế, phương tiện và quản lý người dùng")
public class AdminController {
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminReviewService reviews;
    private final AdminUserService adminUsers;

    public AdminController(AdminReviewService reviews, AdminUserService adminUsers) {
        this.reviews = reviews;
        this.adminUsers = adminUsers;
    }

    @GetMapping("/drivers/pending")
    @Operation(summary = "Hồ sơ tài xế chờ duyệt (cũ nhất trước)")
    public ApiResponse<PageResponse<AdminDriverResponse>> pendingDrivers(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return ok(reviews.pendingDrivers(page(page, size, Sort.by("submittedAt").ascending())), request);
    }

    @PostMapping("/drivers/{id}/approve")
    @Operation(summary = "Duyệt hồ sơ tài xế")
    public ApiResponse<AdminDriverResponse> approveDriver(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Driver approved", reviews.decideDriver(me.id(), id, true, null), request.getRequestURI());
    }

    @PostMapping("/drivers/{id}/reject")
    @Operation(summary = "Từ chối hồ sơ tài xế (bắt buộc lý do)")
    public ApiResponse<AdminDriverResponse> rejectDriver(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, @Valid @RequestBody RejectRequest body, HttpServletRequest request) {
        return ApiResponse.success("Driver rejected", reviews.decideDriver(me.id(), id, false, body.reason()), request.getRequestURI());
    }

    @GetMapping("/vehicles/pending")
    @Operation(summary = "Phương tiện chờ duyệt (cũ nhất trước)")
    public ApiResponse<PageResponse<AdminVehicleResponse>> pendingVehicles(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return ok(reviews.pendingVehicles(page(page, size, Sort.by("submittedAt").ascending())), request);
    }

    @PostMapping("/vehicles/{id}/approve")
    @Operation(summary = "Duyệt phương tiện")
    public ApiResponse<AdminVehicleResponse> approveVehicle(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Vehicle approved", reviews.decideVehicle(me.id(), id, true, null), request.getRequestURI());
    }

    @PostMapping("/vehicles/{id}/reject")
    @Operation(summary = "Từ chối phương tiện (bắt buộc lý do)")
    public ApiResponse<AdminVehicleResponse> rejectVehicle(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, @Valid @RequestBody RejectRequest body, HttpServletRequest request) {
        return ApiResponse.success("Vehicle rejected", reviews.decideVehicle(me.id(), id, false, body.reason()), request.getRequestURI());
    }

    @GetMapping("/users")
    @Operation(summary = "Danh sách người dùng, lọc theo vai trò/trạng thái")
    public ApiResponse<PageResponse<UserResponse>> users(@RequestParam(required = false) UserRole role, @RequestParam(required = false) UserStatus status,
                                                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return ok(adminUsers.search(role, status, page(page, size, Sort.by("createdAt").descending())), request);
    }

    @PostMapping("/users/{id}/status")
    @Operation(summary = "Đổi trạng thái tài khoản (ACTIVE/SUSPENDED/LOCKED)", description = "Khóa/tạm ngưng sẽ thu hồi toàn bộ phiên của người dùng.")
    public ApiResponse<UserResponse> changeStatus(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, @Valid @RequestBody ChangeUserStatusRequest body, HttpServletRequest request) {
        return ApiResponse.success("User status updated", adminUsers.changeStatus(me.id(), id, body.status(), body.reason()), request.getRequestURI());
    }

    private static Pageable page(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort);
    }

    private static <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", data, request.getRequestURI());
    }
}
