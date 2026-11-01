package com.truckgo.notification;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.notification.NotificationService.NotificationResponse;
import com.truckgo.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Notification", description = "Thông báo trong ứng dụng của người dùng hiện tại")
public class NotificationController {
    private final NotificationService notifications;
    private final NotificationStreamHub hub;
    private final NotificationProperties properties;

    public NotificationController(NotificationService notifications, NotificationStreamHub hub, NotificationProperties properties) {
        this.notifications = notifications;
        this.hub = hub;
        this.properties = properties;
    }

    /**
     * Server-sent events "notification" for the authenticated user only. A reconnecting client sends the id of the last
     * notification it saw (Last-Event-ID header or lastEventId parameter) and first receives what it missed.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Luồng thông báo realtime (SSE) của người dùng hiện tại")
    public SseEmitter stream(@AuthenticationPrincipal AuthenticatedUser me, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventHeader,
                             @RequestParam(required = false) String lastEventId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        UUID last = parse(lastEventHeader != null ? lastEventHeader : lastEventId);
        List<NotificationResponse> missed = last == null ? List.of() : notifications.since(me.id(), last, properties.replayLimit());
        return hub.open(me.id(), missed);
    }

    private static UUID parse(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value.trim()); } catch (IllegalArgumentException ex) { return null; }
    }

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
