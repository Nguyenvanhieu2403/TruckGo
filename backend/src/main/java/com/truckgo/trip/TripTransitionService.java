package com.truckgo.trip;

import com.truckgo.common.exception.BusinessRuleException;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverProfileRepository;
import com.truckgo.notification.NotificationService;
import com.truckgo.notification.NotificationType;
import com.truckgo.shipment.AttachmentFileType;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentAttachment;
import com.truckgo.shipment.ShipmentAttachmentRepository;
import com.truckgo.shipment.ShipmentDeliveredEvent;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.shipment.ShipmentStatusHistory;
import com.truckgo.shipment.ShipmentStatusHistoryRepository;
import com.truckgo.shipment.ShipmentStop;
import com.truckgo.trip.dto.TripDtos.MilestoneRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only way a trip changes status. Every action takes the order's row lock, resolves the driver from the
 * authenticated user, checks the order is assigned to them and in the expected status, then changes the status
 * through {@link Shipment} (which enforces {@link ShipmentStatus#canTransitionTo}), records the history and
 * notifies the customer, all in one transaction.
 *
 * <p>Stops are handled strictly in sequence: the first stop (the pickup) drives ARRIVED/PICKED_UP, intermediate stops
 * are arrived at and completed while IN_TRANSIT, and delivering completes the last stop only once every earlier stop
 * is done. Stops cannot be skipped.
 */
@Service
public class TripTransitionService {
    private static final Logger log = LoggerFactory.getLogger(TripTransitionService.class);

    private final ShipmentRepository shipments;
    private final DriverProfileRepository drivers;
    private final StopProgressRepository progress;
    private final ShipmentStatusHistoryRepository statusHistory;
    private final ShipmentAttachmentRepository attachments;
    private final MilestoneAttachmentRepository milestoneAttachments;
    private final TrackingService tracking;
    private final DeliveryConfirmationService confirmations;
    private final NotificationService notifications;
    private final TripEventHub events;
    private final ApplicationEventPublisher publisher;
    private final TripProperties properties;
    private final Clock clock;

    public TripTransitionService(ShipmentRepository shipments, DriverProfileRepository drivers, StopProgressRepository progress,
                                 ShipmentStatusHistoryRepository statusHistory, ShipmentAttachmentRepository attachments,
                                 MilestoneAttachmentRepository milestoneAttachments, TrackingService tracking, DeliveryConfirmationService confirmations,
                                 NotificationService notifications, TripEventHub events, TripProperties properties, Clock clock,
                                 ApplicationEventPublisher publisher) {
        this.publisher = publisher;
        this.shipments = shipments;
        this.drivers = drivers;
        this.progress = progress;
        this.statusHistory = statusHistory;
        this.attachments = attachments;
        this.milestoneAttachments = milestoneAttachments;
        this.tracking = tracking;
        this.confirmations = confirmations;
        this.notifications = notifications;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** ASSIGNED -> DRIVER_ARRIVED at the first stop. */
    @Transactional
    public void arrived(UUID userId, UUID shipmentId, MilestoneRequest request) {
        Trip trip = lock(userId, shipmentId);
        require(trip.shipment(), ShipmentStatus.ASSIGNED);
        Instant now = clock.instant();
        trip.stops().getFirst().arrive(now, clean(request.note()), fix(request));
        trip.shipment().markDriverArrived(now);
        finish(trip, ShipmentStatus.ASSIGNED, request.note(), "Tài xế đã đến điểm lấy hàng", "Tài xế đã đến điểm lấy hàng của đơn ");
    }

    /** DRIVER_ARRIVED -> PICKED_UP: the goods at the first stop are loaded. */
    @Transactional
    public void pickup(UUID userId, UUID shipmentId, MilestoneRequest request) {
        Trip trip = lock(userId, shipmentId);
        require(trip.shipment(), ShipmentStatus.DRIVER_ARRIVED);
        Instant now = clock.instant();
        link(trip, request.attachmentIds(), AttachmentFileType.PICKUP_PHOTO, MilestoneAttachment.Milestone.PICKUP, userId);
        trip.stops().getFirst().complete(now, clean(request.note()), fix(request));
        trip.shipment().markPickedUp(now);
        finish(trip, ShipmentStatus.DRIVER_ARRIVED, request.note(), "Tài xế đã nhận hàng", "Tài xế đã nhận hàng của đơn ");
    }

    /** PICKED_UP -> IN_TRANSIT; location sharing becomes possible from here on. */
    @Transactional
    public void start(UUID userId, UUID shipmentId, MilestoneRequest request) {
        Trip trip = lock(userId, shipmentId);
        require(trip.shipment(), ShipmentStatus.PICKED_UP);
        Instant now = clock.instant();
        trip.shipment().markInTransit(now);
        tracking.startSession(trip.shipment(), now);
        finish(trip, ShipmentStatus.PICKED_UP, request.note(), "Bắt đầu vận chuyển", "Đơn đang được vận chuyển: ");
    }

    /** Arrival at the next intermediate stop (in sequence) while in transit. */
    @Transactional
    public void arriveStop(UUID userId, UUID shipmentId, UUID stopId, MilestoneRequest request) {
        Trip trip = lock(userId, shipmentId);
        require(trip.shipment(), ShipmentStatus.IN_TRANSIT);
        StopProgress stop = nextIntermediate(trip, stopId);
        stop.arrive(clock.instant(), clean(request.note()), fix(request));
        progress.flush();
        events.publishStatus(trip.shipment());
    }

    /** Leaving an intermediate stop once its goods are loaded/unloaded. */
    @Transactional
    public void completeStop(UUID userId, UUID shipmentId, UUID stopId, MilestoneRequest request) {
        Trip trip = lock(userId, shipmentId);
        require(trip.shipment(), ShipmentStatus.IN_TRANSIT);
        StopProgress stop = nextIntermediate(trip, stopId);
        stop.complete(clock.instant(), clean(request.note()), fix(request));
        progress.flush();
        notifyCustomer(trip.shipment(), "Đơn " + trip.shipment().getShipmentCode() + " đã qua điểm dừng " + stop.getSequenceNo(),
                "Tài xế đã hoàn thành điểm dừng số " + stop.getSequenceNo() + ".");
        events.publishStatus(trip.shipment());
    }

    /** IN_TRANSIT -> DELIVERED at the last stop; issues the customer's confirmation code. */
    @Transactional
    public void deliver(UUID userId, UUID shipmentId, MilestoneRequest request) {
        Trip trip = lock(userId, shipmentId);
        require(trip.shipment(), ShipmentStatus.IN_TRANSIT);
        List<StopProgress> stops = trip.stops();
        if (stops.subList(1, stops.size() - 1).stream().anyMatch(s -> !s.isDone()))
            throw BusinessRuleException.conflict(TripErrors.STOPS_INCOMPLETE, "Còn điểm dừng chưa hoàn thành, không thể xác nhận giao hàng");
        if (properties.requireDeliveryPhoto() && request.attachmentIds().isEmpty())
            throw TripErrors.unprocessable(TripErrors.PHOTO_REQUIRED, "Cần ít nhất một ảnh bằng chứng giao hàng");
        Instant now = clock.instant();
        link(trip, request.attachmentIds(), AttachmentFileType.DELIVERY_PHOTO, MilestoneAttachment.Milestone.DELIVERY, userId);
        stops.getLast().complete(now, clean(request.note()), fix(request));
        trip.shipment().markDelivered(now);
        tracking.endSession(trip.shipment().getId(), now);
        finish(trip, ShipmentStatus.IN_TRANSIT, request.note(), "Tài xế xác nhận đã giao hàng", "Tài xế đã giao hàng đơn ");
        confirmations.issue(trip.shipment());
        publisher.publishEvent(new ShipmentDeliveredEvent(trip.shipment().getId()));
    }

    // ------------------------------------------------------------------ helpers

    record Trip(Shipment shipment, DriverProfile driver, List<StopProgress> stops) { }

    /** Locks the order and checks it is assigned to the calling driver; anyone else gets 404. */
    Trip lock(UUID userId, UUID shipmentId) {
        DriverProfile driver = drivers.findByUserId(userId).orElseThrow(TripTransitionService::notFound);
        Shipment shipment = shipments.findByIdForUpdate(shipmentId).filter(s -> s.isAssignedTo(driver.getId())).orElseThrow(TripTransitionService::notFound);
        return new Trip(shipment, driver, ensureProgress(shipment, driver.getId()));
    }

    /** Creates the PENDING stop rows the first time a trip is touched (orders assigned before Sprint 6 included). */
    List<StopProgress> ensureProgress(Shipment shipment, UUID driverId) {
        List<StopProgress> rows = progress.findByShipmentIdOrderBySequenceNoAsc(shipment.getId());
        if (!rows.isEmpty()) return rows;
        List<ShipmentStop> stops = shipment.getStops().stream().sorted(Comparator.comparingInt(ShipmentStop::getSequenceNo)).toList();
        rows = stops.stream().map(s -> new StopProgress(shipment.getId(), s.getId(), driverId, s.getSequenceNo())).toList();
        return progress.saveAllAndFlush(rows).stream().sorted(Comparator.comparingInt(StopProgress::getSequenceNo)).toList();
    }

    /** The given stop must be the first unfinished intermediate stop; the last stop is only completed by deliver. */
    private static StopProgress nextIntermediate(Trip trip, UUID stopId) {
        List<StopProgress> stops = trip.stops();
        Optional<StopProgress> next = stops.subList(1, Math.max(stops.size() - 1, 1)).stream().filter(s -> !s.isDone()).findFirst();
        if (next.isEmpty() || !next.get().getStopId().equals(stopId)) {
            if (stops.stream().noneMatch(s -> s.getStopId().equals(stopId))) throw new ResourceNotFoundException("Không tìm thấy điểm dừng");
            throw BusinessRuleException.conflict(TripErrors.STOP_OUT_OF_ORDER, next.isEmpty()
                    ? "Không còn điểm dừng trung gian; hãy xác nhận giao hàng ở điểm cuối"
                    : "Cần hoàn thành điểm dừng số " + next.get().getSequenceNo() + " trước");
        }
        return next.get();
    }

    private static void require(Shipment shipment, ShipmentStatus expected) {
        if (shipment.getStatus() != expected) throw TripErrors.status(expected.name(), shipment.getStatus());
    }

    /** Proof photos must be this driver's own uploads of the right kind for this order, used only once. */
    private void link(Trip trip, List<UUID> ids, AttachmentFileType type, MilestoneAttachment.Milestone milestone, UUID userId) {
        for (UUID id : new HashSet<>(ids)) {
            ShipmentAttachment a = attachments.findByIdAndShipmentId(id, trip.shipment().getId())
                    .filter(x -> x.getFileType() == type && x.getUploadedBy().equals(userId))
                    .orElseThrow(() -> FieldErrorException.invalid("attachments", "Ảnh không hợp lệ cho bước này"));
            if (milestoneAttachments.existsByAttachmentId(a.getId())) throw FieldErrorException.invalid("attachments", "Ảnh đã được dùng");
            milestoneAttachments.save(new MilestoneAttachment(trip.shipment().getId(), milestone, a.getId()));
        }
    }

    private void finish(Trip trip, ShipmentStatus previous, String note, String defaultReason, String notifyPrefix) {
        Shipment s = trip.shipment();
        shipments.flush();
        statusHistory.save(new ShipmentStatusHistory(s.getId(), previous, s.getStatus(), trip.driver().getUserId(),
                clean(note) == null ? defaultReason : defaultReason + ": " + clean(note)));
        notifyCustomer(s, notifyPrefix + s.getShipmentCode(), defaultReason + " (đơn " + s.getShipmentCode() + ").");
        events.publishStatus(s);
        log.info("Trip status shipment={} {} -> {}", s.getId(), previous, s.getStatus());
    }

    private void notifyCustomer(Shipment s, String title, String content) {
        notifications.notify(s.getCustomerId(), NotificationType.TRIP_STATUS_CHANGED, title, content, "SHIPMENT", s.getId());
    }

    private static GeoFix fix(MilestoneRequest request) { return GeoFix.optional(request.latitude(), request.longitude()); }

    static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    static ResourceNotFoundException notFound() { return new ResourceNotFoundException("Không tìm thấy chuyến vận chuyển"); }
}
