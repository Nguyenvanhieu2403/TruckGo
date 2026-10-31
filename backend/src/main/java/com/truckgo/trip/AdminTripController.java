package com.truckgo.trip;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.trip.dto.TripDtos.CustomerTripView;
import com.truckgo.trip.dto.TripDtos.IncidentUpdateRequest;
import com.truckgo.trip.dto.TripDtos.IncidentView;
import com.truckgo.trip.dto.TripDtos.TrackingPoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin Trips", description = "Theo dõi chuyến và xử lý sự cố")
public class AdminTripController {
    private final TripQueryService queries;
    private final TrackingService tracking;
    private final IncidentService incidents;

    public AdminTripController(TripQueryService queries, TrackingService tracking, IncidentService incidents) {
        this.queries = queries;
        this.tracking = tracking;
        this.incidents = incidents;
    }

    @GetMapping("/trips/{shipmentId}")
    @Operation(summary = "Chuyến của một đơn (kể cả vị trí mới nhất)")
    public ApiResponse<CustomerTripView> trip(@PathVariable UUID shipmentId, HttpServletRequest request) {
        return ok(queries.adminView(shipmentId), request);
    }

    @GetMapping("/trips/{shipmentId}/tracking/history")
    @Operation(summary = "Lịch sử hành trình")
    public ApiResponse<PageResponse<TrackingPoint>> history(@PathVariable UUID shipmentId, @RequestParam(required = false) Instant from,
                                                            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "200") int size, HttpServletRequest request) {
        queries.adminView(shipmentId);
        return ok(tracking.history(shipmentId, from, to, page, size), request);
    }

    @GetMapping("/incidents")
    @Operation(summary = "Danh sách sự cố")
    public ApiResponse<PageResponse<IncidentView>> list(@RequestParam(required = false) IncidentType type, @RequestParam(required = false) IncidentStatus status,
                                                        @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                                        HttpServletRequest request) {
        return ok(incidents.adminList(type, status, from, to, page, size), request);
    }

    @GetMapping("/incidents/{id}")
    @Operation(summary = "Chi tiết sự cố")
    public ApiResponse<IncidentView> get(@PathVariable UUID id, HttpServletRequest request) { return ok(incidents.adminGet(id), request); }

    @PatchMapping("/incidents/{id}")
    @Operation(summary = "Cập nhật tình trạng xử lý (bắt buộc ghi chú khi đóng)")
    public ApiResponse<IncidentView> update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, @Valid @RequestBody IncidentUpdateRequest body,
                                            HttpServletRequest request) {
        return ApiResponse.success("Incident updated", incidents.adminUpdate(me.id(), id, body.status(), body.note()), request.getRequestURI());
    }

    private static <T> ApiResponse<T> ok(T data, HttpServletRequest request) { return ApiResponse.success("Request completed successfully", data, request.getRequestURI()); }
}
