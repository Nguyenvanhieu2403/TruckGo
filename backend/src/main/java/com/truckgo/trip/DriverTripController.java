package com.truckgo.trip;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.shipment.AttachmentFileType;
import com.truckgo.shipment.ShipmentAttachmentService;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.trip.dto.TripDtos.DriverTripDetail;
import com.truckgo.trip.dto.TripDtos.IncidentRequest;
import com.truckgo.trip.dto.TripDtos.IncidentView;
import com.truckgo.trip.dto.TripDtos.LocationAck;
import com.truckgo.trip.dto.TripDtos.LocationRequest;
import com.truckgo.trip.dto.TripDtos.MilestoneRequest;
import com.truckgo.trip.dto.TripDtos.PhotoView;
import com.truckgo.trip.dto.TripDtos.TrackingPoint;
import com.truckgo.trip.dto.TripDtos.TrackingView;
import com.truckgo.trip.dto.TripDtos.TripSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/driver/trips")
@PreAuthorize("hasRole('DRIVER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Driver Trips", description = "Tài xế thực hiện chuyến: mốc trạng thái, điểm dừng, GPS, ảnh, sự cố")
public class DriverTripController {
    private static final MilestoneRequest EMPTY = new MilestoneRequest(null, null, null, null);
    private final TripQueryService queries;
    private final TripTransitionService transitions;
    private final TrackingService tracking;
    private final TripPhotoService photos;
    private final IncidentService incidents;

    public DriverTripController(TripQueryService queries, TripTransitionService transitions, TrackingService tracking, TripPhotoService photos,
                                IncidentService incidents) {
        this.queries = queries;
        this.transitions = transitions;
        this.tracking = tracking;
        this.photos = photos;
        this.incidents = incidents;
    }

    @GetMapping
    @Operation(summary = "Chuyến được phân công", description = "view: ACTIVE (đang thực hiện), UPCOMING (sắp thực hiện), COMPLETED, INCIDENT (có sự cố mở), ALL")
    public ApiResponse<PageResponse<TripSummary>> list(@AuthenticationPrincipal AuthenticatedUser me, @RequestParam(defaultValue = "ALL") TripQueryService.View view,
                                                       @RequestParam(required = false) ShipmentStatus status,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                                       @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return ok(queries.list(me.id(), view, status, fromDate, toDate, keyword, page, size), request);
    }

    @GetMapping("/{shipmentId}")
    @Operation(summary = "Chi tiết chuyến")
    public ApiResponse<DriverTripDetail> detail(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ok(queries.driverDetail(me.id(), shipmentId), request);
    }

    @PostMapping("/{shipmentId}/arrived")
    @Operation(summary = "Đã đến điểm lấy hàng (ASSIGNED → DRIVER_ARRIVED)")
    public ApiResponse<DriverTripDetail> arrived(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                                 @Valid @RequestBody(required = false) MilestoneRequest body, HttpServletRequest request) {
        transitions.arrived(me.id(), shipmentId, body == null ? EMPTY : body);
        return ok(queries.driverDetail(me.id(), shipmentId), request);
    }

    @PostMapping("/{shipmentId}/pickup")
    @Operation(summary = "Đã nhận hàng (DRIVER_ARRIVED → PICKED_UP)")
    public ApiResponse<DriverTripDetail> pickup(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                                @Valid @RequestBody(required = false) MilestoneRequest body, HttpServletRequest request) {
        transitions.pickup(me.id(), shipmentId, body == null ? EMPTY : body);
        return ok(queries.driverDetail(me.id(), shipmentId), request);
    }

    @PostMapping("/{shipmentId}/start")
    @Operation(summary = "Bắt đầu vận chuyển (PICKED_UP → IN_TRANSIT)")
    public ApiResponse<DriverTripDetail> start(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                               @Valid @RequestBody(required = false) MilestoneRequest body, HttpServletRequest request) {
        transitions.start(me.id(), shipmentId, body == null ? EMPTY : body);
        return ok(queries.driverDetail(me.id(), shipmentId), request);
    }

    @PostMapping("/{shipmentId}/stops/{stopId}/arrive")
    @Operation(summary = "Đến điểm dừng trung gian tiếp theo")
    public ApiResponse<DriverTripDetail> arriveStop(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID stopId,
                                                    @Valid @RequestBody(required = false) MilestoneRequest body, HttpServletRequest request) {
        transitions.arriveStop(me.id(), shipmentId, stopId, body == null ? EMPTY : body);
        return ok(queries.driverDetail(me.id(), shipmentId), request);
    }

    @PostMapping("/{shipmentId}/stops/{stopId}/complete")
    @Operation(summary = "Hoàn thành điểm dừng trung gian")
    public ApiResponse<DriverTripDetail> completeStop(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID stopId,
                                                      @Valid @RequestBody(required = false) MilestoneRequest body, HttpServletRequest request) {
        transitions.completeStop(me.id(), shipmentId, stopId, body == null ? EMPTY : body);
        return ok(queries.driverDetail(me.id(), shipmentId), request);
    }

    @PostMapping("/{shipmentId}/deliver")
    @Operation(summary = "Đã giao hàng (IN_TRANSIT → DELIVERED); gửi mã xác nhận cho khách")
    public ApiResponse<DriverTripDetail> deliver(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                                 @Valid @RequestBody(required = false) MilestoneRequest body, HttpServletRequest request) {
        transitions.deliver(me.id(), shipmentId, body == null ? EMPTY : body);
        return ok(queries.driverDetail(me.id(), shipmentId), request);
    }

    @PostMapping("/{shipmentId}/location")
    @Operation(summary = "Gửi vị trí GPS (chỉ khi IN_TRANSIT và đang chia sẻ)", description = "speed km/h, heading độ, accuracy mét; phản hồi kèm chu kỳ gửi đề xuất")
    public ApiResponse<LocationAck> location(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                             @Valid @RequestBody LocationRequest body, HttpServletRequest request) {
        return ok(tracking.record(me.id(), shipmentId, body), request);
    }

    @PostMapping("/{shipmentId}/tracking/pause")
    @Operation(summary = "Tạm dừng chia sẻ vị trí")
    public ApiResponse<TrackingView> pause(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ok(tracking.pause(me.id(), shipmentId, true), request);
    }

    @PostMapping("/{shipmentId}/tracking/resume")
    @Operation(summary = "Tiếp tục chia sẻ vị trí")
    public ApiResponse<TrackingView> resume(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ok(tracking.pause(me.id(), shipmentId, false), request);
    }

    @GetMapping("/{shipmentId}/tracking/history")
    @Operation(summary = "Lịch sử hành trình của chuyến")
    public ApiResponse<PageResponse<TrackingPoint>> history(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                                            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "200") int size,
                                                            HttpServletRequest request) {
        queries.requireDriverTrip(me.id(), shipmentId);
        return ok(tracking.history(shipmentId, from, to, page, size), request);
    }

    @PostMapping(value = "/{shipmentId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tải ảnh bằng chứng", description = "purpose: PICKUP_PHOTO, DELIVERY_PHOTO, INCIDENT_PHOTO")
    public ApiResponse<PhotoView> upload(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                         @RequestParam AttachmentFileType purpose, @RequestPart("file") MultipartFile file, HttpServletRequest request) {
        return ApiResponse.success("Photo uploaded", photos.upload(me.id(), shipmentId, purpose, file), request.getRequestURI());
    }

    @GetMapping("/{shipmentId}/photos/{attachmentId}/content")
    @Operation(summary = "Nội dung ảnh của chuyến")
    public ResponseEntity<InputStreamResource> photo(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID attachmentId) {
        ShipmentAttachmentService.Content content = photos.open(me.id(), shipmentId, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType())).contentLength(content.size())
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(content.stream()));
    }

    @PostMapping("/{shipmentId}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Báo cáo sự cố")
    public ApiResponse<IncidentView> report(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                            @Valid @RequestBody IncidentRequest body, HttpServletRequest request) {
        return ApiResponse.success("Incident reported", incidents.report(me.id(), shipmentId, body), request.getRequestURI());
    }

    @GetMapping("/{shipmentId}/incidents")
    @Operation(summary = "Sự cố của chuyến")
    public ApiResponse<List<IncidentView>> incidents(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ok(incidents.forDriver(me.id(), shipmentId), request);
    }

    private static <T> ApiResponse<T> ok(T data, HttpServletRequest request) { return ApiResponse.success("Request completed successfully", data, request.getRequestURI()); }
}
