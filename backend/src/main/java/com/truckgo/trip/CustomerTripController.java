package com.truckgo.trip;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.shipment.Shipment;
import com.truckgo.trip.dto.TripDtos.ConfirmDeliveryRequest;
import com.truckgo.trip.dto.TripDtos.ConfirmationView;
import com.truckgo.trip.dto.TripDtos.CustomerTripView;
import com.truckgo.trip.dto.TripDtos.IncidentView;
import com.truckgo.trip.dto.TripDtos.TrackingPoint;
import com.truckgo.trip.dto.TripDtos.TrackingView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customer/shipments/{shipmentId}")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer Trip", description = "Khách hàng theo dõi chuyến, vị trí tài xế và xác nhận nhận hàng")
public class CustomerTripController {
    private final TripQueryService queries;
    private final TrackingService tracking;
    private final DeliveryConfirmationService confirmations;
    private final IncidentService incidents;
    private final TripEventHub events;

    public CustomerTripController(TripQueryService queries, TrackingService tracking, DeliveryConfirmationService confirmations, IncidentService incidents,
                                  TripEventHub events) {
        this.queries = queries;
        this.tracking = tracking;
        this.confirmations = confirmations;
        this.incidents = incidents;
        this.events = events;
    }

    @GetMapping("/trip")
    @Operation(summary = "Chuyến của đơn: tài xế, xe, điểm dừng, mốc thời gian, vị trí")
    public ApiResponse<CustomerTripView> trip(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ok(queries.customerView(me.id(), shipmentId), request);
    }

    @GetMapping("/tracking")
    @Operation(summary = "Vị trí mới nhất (LIVE/STALE/OFFLINE/PAUSED/ENDED, không bao giờ giả lập)")
    public ApiResponse<TrackingView> tracking(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ok(tracking.current(queries.requireCustomerTrip(me.id(), shipmentId)), request);
    }

    @GetMapping("/tracking/history")
    @Operation(summary = "Lịch sử hành trình")
    public ApiResponse<PageResponse<TrackingPoint>> history(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                                            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "200") int size,
                                                            HttpServletRequest request) {
        queries.requireCustomerTrip(me.id(), shipmentId);
        return ok(tracking.history(shipmentId, from, to, page, size), request);
    }

    /**
     * Server-sent events "status" and "location" for this order only. Ownership is checked here, when the stream is
     * opened; the stream closes after app.tracking.stream-timeout and the client reconnects with a fresh token.
     */
    @GetMapping(value = "/tracking/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Luồng realtime (SSE) trạng thái và vị trí")
    public SseEmitter stream(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletResponse response) {
        Shipment shipment = queries.requireCustomerTrip(me.id(), shipmentId);
        response.setHeader("X-Accel-Buffering", "no");   // nginx: pass events through immediately
        response.setHeader("Cache-Control", "no-cache");
        return events.open(shipmentId, tracking.current(shipment), Map.of("status", shipment.getStatus().name()));
    }

    @PostMapping("/confirm-delivery")
    @Operation(summary = "Xác nhận đã nhận hàng bằng mã OTP (DELIVERED → COMPLETED)")
    public ApiResponse<CustomerTripView> confirm(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                                 @Valid @RequestBody ConfirmDeliveryRequest body, HttpServletRequest request) {
        confirmations.confirm(me.id(), shipmentId, body.otp(), body.note());
        return ok(queries.customerView(me.id(), shipmentId), request);
    }

    @PostMapping("/delivery-otp/resend")
    @Operation(summary = "Gửi lại mã xác nhận (có thời gian chờ và giới hạn số lần)")
    public ApiResponse<ConfirmationView> resend(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ApiResponse.success("Code sent", confirmations.resend(me.id(), shipmentId), request.getRequestURI());
    }

    @GetMapping("/incidents")
    @Operation(summary = "Sự cố của chuyến")
    public ApiResponse<List<IncidentView>> incidents(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ok(incidents.forCustomer(me.id(), shipmentId), request);
    }

    private static <T> ApiResponse<T> ok(T data, HttpServletRequest request) { return ApiResponse.success("Request completed successfully", data, request.getRequestURI()); }
}
