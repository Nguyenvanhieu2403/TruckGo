package com.truckgo.notification;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.notification.NotificationService.NotificationResponse;
import com.truckgo.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Notification", description = "Thông báo trong ứng dụng của người dùng hiện tại")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping
    @Operation(summary = "Danh sách thông báo (mới nhất trước)")
    public ApiResponse<PageResponse<NotificationResponse>> list(@AuthenticationPrincipal AuthenticatedUser me, @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                               @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", notifications.list(me.id(), unreadOnly, page, size), request.getRequestURI());
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Số thông báo chưa đọc")
    public ApiResponse<Map<String, Long>> unreadCount(@AuthenticationPrincipal AuthenticatedUser me, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", Map.of("count", notifications.unreadCount(me.id())), request.getRequestURI());
    }

    @PatchMapping("/{id}/read")
    @Operation(summary = "Đánh dấu đã đọc")
    public ApiResponse<NotificationResponse> read(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Notification read", notifications.markRead(me.id(), id), request.getRequestURI());
    }

    @PatchMapping("/read-all")
    @Operation(summary = "Đánh dấu tất cả đã đọc")
    public ApiResponse<Map<String, Integer>> readAll(@AuthenticationPrincipal AuthenticatedUser me, HttpServletRequest request) {
        return ApiResponse.success("Notifications read", Map.of("updated", notifications.markAllRead(me.id())), request.getRequestURI());
    }
}
