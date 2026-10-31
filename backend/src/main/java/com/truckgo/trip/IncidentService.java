package com.truckgo.trip;

import com.truckgo.admin.AdminAuditService;
import com.truckgo.common.exception.BusinessRuleException;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverProfileRepository;
import com.truckgo.notification.NotificationService;
import com.truckgo.notification.NotificationType;
import com.truckgo.shipment.AttachmentFileType;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentAttachmentRepository;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.trip.dto.TripDtos.IncidentRequest;
import com.truckgo.trip.dto.TripDtos.IncidentView;
import com.truckgo.trip.dto.TripDtos.PhotoView;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Trip incidents. The assigned driver reports them, the customer and the driver read them, administrators move them
 * through OPEN -> IN_PROGRESS -> RESOLVED/CANCELLED. Reporting never changes the order's status or driver.
 */
@Service
public class IncidentService {
    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    private static final Set<ShipmentStatus> REPORTABLE = EnumSet.of(ShipmentStatus.ASSIGNED, ShipmentStatus.DRIVER_ARRIVED, ShipmentStatus.PICKED_UP,
            ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED);

    private final IncidentRepository incidents;
    private final ShipmentRepository shipments;
    private final DriverProfileRepository drivers;
    private final UserRepository users;
    private final ShipmentAttachmentRepository attachments;
    private final TripQueryService queries;
    private final NotificationService notifications;
    private final AdminAuditService audit;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, ShipmentRepository shipments, DriverProfileRepository drivers, UserRepository users,
                           ShipmentAttachmentRepository attachments, TripQueryService queries, NotificationService notifications,
                           AdminAuditService audit, Clock clock) {
        this.incidents = incidents;
        this.shipments = shipments;
        this.drivers = drivers;
        this.users = users;
        this.attachments = attachments;
        this.queries = queries;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IncidentView report(UUID userId, UUID shipmentId, IncidentRequest request) {
        Shipment shipment = queries.requireDriverTrip(userId, shipmentId);
        if (!REPORTABLE.contains(shipment.getStatus()))
            throw BusinessRuleException.conflict(TripErrors.INVALID_TRIP_STATUS, "Không thể báo sự cố khi chuyến ở trạng thái " + shipment.getStatus());
        Set<UUID> photoIds = new LinkedHashSet<>(request.attachmentIds());
        for (UUID id : photoIds)
            attachments.findByIdAndShipmentId(id, shipmentId).filter(a -> a.getFileType() == AttachmentFileType.INCIDENT_PHOTO && a.getUploadedBy().equals(userId))
                    .orElseThrow(() -> FieldErrorException.invalid("attachments", "Ảnh không hợp lệ cho sự cố"));
        DriverProfile driver = drivers.findByUserId(userId).orElseThrow();
        Incident incident = new Incident(shipmentId, userId, driver.getId(), request.type(), request.description().trim(),
                GeoFix.optional(request.latitude(), request.longitude()), clock.instant());
        incident.attach(photoIds);
        incident = incidents.saveAndFlush(incident);
        notifications.notify(shipment.getCustomerId(), NotificationType.TRIP_INCIDENT, "Sự cố trên chuyến " + shipment.getShipmentCode(),
                "Tài xế báo sự cố: " + IncidentLabels.label(request.type()) + ". " + abbreviate(request.description().trim()), "SHIPMENT", shipmentId);
        log.info("Incident reported incident={} shipment={} type={}", incident.getId(), shipmentId, request.type());
        return view(incident, shipment, "/api/v1/driver/trips/");
    }

    @Transactional(readOnly = true)
    public List<IncidentView> forDriver(UUID userId, UUID shipmentId) {
        Shipment shipment = queries.requireDriverTrip(userId, shipmentId);
        return incidents.findByShipmentIdOrderByCreatedAtDesc(shipmentId).stream().map(i -> view(i, shipment, "/api/v1/driver/trips/")).toList();
    }

    @Transactional(readOnly = true)
    public List<IncidentView> forCustomer(UUID customerId, UUID shipmentId) {
        Shipment shipment = queries.requireCustomerTrip(customerId, shipmentId);
        return incidents.findByShipmentIdOrderByCreatedAtDesc(shipmentId).stream().map(i -> view(i, shipment, "/api/v1/shipments/")).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<IncidentView> adminList(IncidentType type, IncidentStatus status, Instant from, Instant to, int page, int size) {
        Specification<Incident> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (type != null) p.add(cb.equal(root.get("type"), type));
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            if (to != null) p.add(cb.lessThan(root.get("occurredAt"), to));
            return cb.and(p.toArray(Predicate[]::new));
        };
        var rows = incidents.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(rows, i -> view(i, shipments.findById(i.getShipmentId()).orElse(null), "/api/v1/shipments/"));
    }

    @Transactional(readOnly = true)
    public IncidentView adminGet(UUID id) {
        Incident i = incidents.findById(id).orElseThrow(IncidentService::notFound);
        return view(i, shipments.findById(i.getShipmentId()).orElse(null), "/api/v1/shipments/");
    }

    @Transactional
    public IncidentView adminUpdate(UUID adminId, UUID id, IncidentStatus target, String note) {
        Incident i = incidents.findById(id).orElseThrow(IncidentService::notFound);
        IncidentStatus previous = i.getStatus();
        String clean = TripTransitionService.clean(note);
        if (target.isClosed() && clean == null) throw FieldErrorException.invalid("note", "Nhập kết quả xử lý khi đóng sự cố");
        i.moveTo(target, adminId, clean, clock.instant());
        incidents.flush();
        audit.record(adminId, "INCIDENT_" + target.name(), "SHIPMENT_INCIDENT", id, previous + " -> " + target + (clean == null ? "" : ": " + clean));
        Shipment shipment = shipments.findById(i.getShipmentId()).orElse(null);
        if (shipment != null) {
            String text = "Sự cố " + IncidentLabels.label(i.getType()) + " trên chuyến " + shipment.getShipmentCode() + " chuyển sang " + IncidentLabels.label(target)
                    + (clean == null ? "." : ": " + clean);
            notifications.notify(shipment.getCustomerId(), NotificationType.INCIDENT_UPDATED, "Cập nhật sự cố đơn " + shipment.getShipmentCode(), text, "SHIPMENT", shipment.getId());
            notifications.notify(i.getReportedBy(), NotificationType.INCIDENT_UPDATED, "Cập nhật sự cố đơn " + shipment.getShipmentCode(), text, "SHIPMENT", shipment.getId());
        }
        return view(i, shipment, "/api/v1/shipments/");
    }

    private IncidentView view(Incident i, Shipment s, String base) {
        String prefix = base.startsWith("/api/v1/driver") ? base + i.getShipmentId() + "/photos/" : base + i.getShipmentId() + "/attachments/";
        List<PhotoView> photos = attachments.findAllById(i.getAttachmentIds()).stream()
                .map(a -> new PhotoView(a.getId(), a.getFileType(), a.getFileName(), a.getFileSize(), a.getCreatedAt(), prefix + a.getId() + "/content")).toList();
        String reporter = users.findById(i.getReportedBy()).map(User::getFullName).orElse(null);
        return new IncidentView(i.getId(), i.getShipmentId(), s == null ? null : s.getShipmentCode(), i.getType(), i.getDescription(), i.getStatus(),
                i.getLatitude(), i.getLongitude(), i.getOccurredAt(), i.getReportedBy(), reporter, i.getResolutionNote(), i.getResolvedAt(), i.getCreatedAt(), photos);
    }

    private static String abbreviate(String text) { return text.length() > 200 ? text.substring(0, 200) + "…" : text; }

    private static ResourceNotFoundException notFound() { return new ResourceNotFoundException("Không tìm thấy sự cố"); }
}
