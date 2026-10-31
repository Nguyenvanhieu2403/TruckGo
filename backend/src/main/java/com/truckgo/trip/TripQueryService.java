package com.truckgo.trip;

import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverProfileRepository;
import com.truckgo.pricing.quote.QuoteMapper;
import com.truckgo.pricing.quote.QuoteStatus;
import com.truckgo.pricing.quote.ShipmentQuoteRepository;
import com.truckgo.shipment.AttachmentFileType;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentAttachment;
import com.truckgo.shipment.ShipmentAttachmentRepository;
import com.truckgo.shipment.ShipmentMapper;
import com.truckgo.shipment.ShipmentProperties;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.shipment.ShipmentStatusHistoryRepository;
import com.truckgo.shipment.ShipmentStop;
import com.truckgo.shipment.StopType;
import com.truckgo.shipment.dto.ShipmentResponses.ShipmentDetail;
import com.truckgo.trip.dto.TripDtos.CustomerTripView;
import com.truckgo.trip.dto.TripDtos.DriverTripDetail;
import com.truckgo.trip.dto.TripDtos.DriverView;
import com.truckgo.trip.dto.TripDtos.Milestones;
import com.truckgo.trip.dto.TripDtos.NextAction;
import com.truckgo.trip.dto.TripDtos.PhotoView;
import com.truckgo.trip.dto.TripDtos.StopProgressView;
import com.truckgo.trip.dto.TripDtos.TripSummary;
import com.truckgo.trip.dto.TripDtos.VehicleView;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import com.truckgo.vehicle.Vehicle;
import com.truckgo.vehicle.VehicleRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read side of trips for the assigned driver, the customer who owns the order and administrators. */
@Service
public class TripQueryService {
    /** Driver list views: what the tabs of the trip list show. */
    public enum View { ACTIVE, UPCOMING, COMPLETED, INCIDENT, ALL }

    static final Set<ShipmentStatus> TRIP_STATUSES = EnumSet.of(ShipmentStatus.ASSIGNED, ShipmentStatus.DRIVER_ARRIVED, ShipmentStatus.PICKED_UP,
            ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED, ShipmentStatus.COMPLETED, ShipmentStatus.CANCELLED);
    private static final Set<IncidentStatus> OPEN_INCIDENTS = EnumSet.of(IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS);

    private final ShipmentRepository shipments;
    private final DriverProfileRepository drivers;
    private final UserRepository users;
    private final VehicleRepository vehicles;
    private final StopProgressRepository progress;
    private final ShipmentStatusHistoryRepository history;
    private final ShipmentAttachmentRepository attachments;
    private final ShipmentQuoteRepository quotes;
    private final IncidentRepository incidents;
    private final ShipmentMapper shipmentMapper;
    private final QuoteMapper quoteMapper;
    private final TrackingService tracking;
    private final DeliveryConfirmationService confirmations;
    private final TripProperties tripProperties;
    private final ShipmentProperties shipmentProperties;

    public TripQueryService(ShipmentRepository shipments, DriverProfileRepository drivers, UserRepository users, VehicleRepository vehicles,
                            StopProgressRepository progress, ShipmentStatusHistoryRepository history, ShipmentAttachmentRepository attachments,
                            ShipmentQuoteRepository quotes, IncidentRepository incidents, ShipmentMapper shipmentMapper, QuoteMapper quoteMapper,
                            TrackingService tracking, DeliveryConfirmationService confirmations, TripProperties tripProperties,
                            ShipmentProperties shipmentProperties) {
        this.shipments = shipments;
        this.drivers = drivers;
        this.users = users;
        this.vehicles = vehicles;
        this.progress = progress;
        this.history = history;
        this.attachments = attachments;
        this.quotes = quotes;
        this.incidents = incidents;
        this.shipmentMapper = shipmentMapper;
        this.quoteMapper = quoteMapper;
        this.tracking = tracking;
        this.confirmations = confirmations;
        this.tripProperties = tripProperties;
        this.shipmentProperties = shipmentProperties;
    }

    // ------------------------------------------------------------------ driver

    @Transactional(readOnly = true)
    public PageResponse<TripSummary> list(UUID userId, View view, ShipmentStatus status, LocalDate fromDate, LocalDate toDate, String keyword, int page, int size) {
        DriverProfile driver = drivers.findByUserId(userId).orElse(null);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50),
                view == View.COMPLETED ? Sort.by(Sort.Direction.DESC, "completedAt", "pickupTime") : Sort.by(Sort.Direction.ASC, "pickupTime"));
        if (driver == null) return new PageResponse<>(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0);
        UUID driverId = driver.getId();
        Specification<Shipment> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("assignedDriverId"), driverId));
            Set<ShipmentStatus> statuses = switch (view == null ? View.ALL : view) {
                case ACTIVE -> EnumSet.of(ShipmentStatus.DRIVER_ARRIVED, ShipmentStatus.PICKED_UP, ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED);
                case UPCOMING -> EnumSet.of(ShipmentStatus.ASSIGNED);
                case COMPLETED -> EnumSet.of(ShipmentStatus.COMPLETED);
                case INCIDENT, ALL -> TRIP_STATUSES;
            };
            p.add(root.get("status").in(statuses));
            if (status != null) p.add(cb.equal(root.get("status"), status));
            var zone = shipmentProperties.businessZone();
            if (fromDate != null) p.add(cb.greaterThanOrEqualTo(root.get("pickupTime"), fromDate.atStartOfDay(zone).toInstant()));
            if (toDate != null) p.add(cb.lessThan(root.get("pickupTime"), toDate.plusDays(1).atStartOfDay(zone).toInstant()));
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                var stops = root.join("stops");
                q.distinct(true);
                p.add(cb.or(cb.like(cb.lower(root.get("shipmentCode")), like, '\\'), cb.like(cb.lower(stops.get("fullAddress")), like, '\\')));
            }
            if (view == View.INCIDENT) {
                Subquery<UUID> open = q.subquery(UUID.class);
                var incident = open.from(Incident.class);
                open.select(incident.get("id")).where(cb.equal(incident.get("shipmentId"), root.get("id")), incident.get("status").in(OPEN_INCIDENTS));
                p.add(cb.exists(open));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        var rows = shipments.findAll(spec, pageable);
        Map<UUID, Vehicle> vehicleById = byId(vehicles.findAllById(rows.getContent().stream().map(Shipment::getAssignedVehicleId).toList()), Vehicle::getId);
        return PageResponse.of(rows, s -> summary(s, vehicleById.get(s.getAssignedVehicleId())));
    }

    @Transactional(readOnly = true)
    public DriverTripDetail driverDetail(UUID userId, UUID shipmentId) {
        DriverProfile driver = drivers.findByUserId(userId).orElseThrow(TripTransitionService::notFound);
        Shipment s = shipments.findById(shipmentId).filter(x -> x.isAssignedTo(driver.getId())).orElseThrow(TripTransitionService::notFound);
        ShipmentDetail detail = shipmentMapper.toDetail(s, List.of(), history.findByShipmentIdOrderByCreatedAtAsc(shipmentId), false);
        User customer = users.findById(s.getCustomerId()).orElse(null);
        List<StopProgressView> stops = stops(s, true);
        StopProgressView next = stops.stream().filter(x -> x.status() != StopStatus.COMPLETED && x.status() != StopStatus.SKIPPED).findFirst().orElse(null);
        NextAction action = nextAction(s, stops, next);
        boolean awaiting = s.getStatus() == ShipmentStatus.DELIVERED && confirmations.current(shipmentId).map(c -> c.pending() || c.locked()).orElse(false);
        return new DriverTripDetail(s.getId(), s.getShipmentCode(), s.getStatus(), s.getPickupTime(), s.getDeliveryDeadline(),
                customer == null ? null : customer.getFullName(), customer == null ? null : customer.getPhone(), detail.vehicle(),
                vehicle(vehicles.findById(s.getAssignedVehicleId()).orElse(null)), s.getTotalQuantity(), s.getTotalWeightKg(), s.getTotalVolumeM3(),
                s.getCustomerNote(), stops, detail.items(), detail.services(),
                quotes.findFirstByShipmentIdAndStatusIn(shipmentId, List.of(QuoteStatus.ACCEPTED)).map(q -> quoteMapper.toResponse(q, s, false)).orElse(null),
                milestones(s), detail.statusHistory(), photos(s, "/api/v1/driver/trips/"), action,
                action == NextAction.ARRIVE_STOP || action == NextAction.COMPLETE_STOP ? next.stopId() : null,
                s.getStatus() == ShipmentStatus.IN_TRANSIT, tracking.current(s).session(), tracking.policy(), tripProperties.requireDeliveryPhoto(),
                awaiting, incidents.countByShipmentIdAndStatusIn(shipmentId, OPEN_INCIDENTS));
    }

    // ------------------------------------------------------------------ customer / admin

    @Transactional(readOnly = true)
    public CustomerTripView customerView(UUID customerId, UUID shipmentId) {
        return view(requireCustomerTrip(customerId, shipmentId), "/api/v1/shipments/");
    }

    @Transactional(readOnly = true)
    public CustomerTripView adminView(UUID shipmentId) {
        Shipment s = shipments.findById(shipmentId).filter(x -> x.isAssigned()).orElseThrow(TripTransitionService::notFound);
        return view(s, "/api/v1/shipments/");
    }

    /** The owner's order once a driver was assigned; anything else is 404. */
    public Shipment requireCustomerTrip(UUID customerId, UUID shipmentId) {
        return shipments.findById(shipmentId).filter(s -> s.isOwnedBy(customerId) && s.isAssigned())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy chuyến vận chuyển"));
    }

    /** The assigned driver's trip, without locking. */
    public Shipment requireDriverTrip(UUID userId, UUID shipmentId) {
        DriverProfile driver = drivers.findByUserId(userId).orElseThrow(TripTransitionService::notFound);
        return shipments.findById(shipmentId).filter(s -> s.isAssignedTo(driver.getId())).orElseThrow(TripTransitionService::notFound);
    }

    private CustomerTripView view(Shipment s, String photoBase) {
        DriverProfile driver = drivers.findById(s.getAssignedDriverId()).orElse(null);
        User driverUser = driver == null ? null : users.findById(driver.getUserId()).orElse(null);
        // The driver's phone is useful while the trip runs, not afterwards.
        boolean active = s.getStatus() != ShipmentStatus.COMPLETED && s.getStatus() != ShipmentStatus.CANCELLED;
        DriverView driverView = driver == null ? null : new DriverView(driver.getId(), driverUser == null ? null : driverUser.getFullName(),
                active && driverUser != null ? driverUser.getPhone() : null, driver.getLicenseClass());
        return new CustomerTripView(s.getId(), s.getShipmentCode(), s.getStatus(), driverView, vehicle(vehicles.findById(s.getAssignedVehicleId()).orElse(null)),
                stops(s, true), milestones(s), shipmentMapper.toDetail(s, List.of(), history.findByShipmentIdOrderByCreatedAtAsc(s.getId()), false).statusHistory(),
                photos(s, photoBase), tracking.current(s),
                // No routing data the app could trust for an ETA exists yet; null rather than a guess.
                null,
                s.getStatus() == ShipmentStatus.DELIVERED ? confirmations.current(s.getId()).orElse(null) : null,
                incidents.countByShipmentIdAndStatusIn(s.getId(), OPEN_INCIDENTS));
    }

    // ------------------------------------------------------------------ mapping

    List<StopProgressView> stops(Shipment s, boolean reveal) {
        Map<UUID, StopProgress> byStop = progress.findByShipmentIdOrderBySequenceNoAsc(s.getId()).stream()
                .collect(Collectors.toMap(StopProgress::getStopId, Function.identity()));
        List<ShipmentStop> ordered = s.getStops().stream().sorted(Comparator.comparingInt(ShipmentStop::getSequenceNo)).toList();
        UUID current = ordered.stream().filter(x -> Optional.ofNullable(byStop.get(x.getId())).map(p -> !p.isDone()).orElse(true))
                .findFirst().map(ShipmentStop::getId).orElse(null);
        boolean tripRunning = TRIP_STATUSES.contains(s.getStatus()) && s.getStatus() != ShipmentStatus.COMPLETED && s.getStatus() != ShipmentStatus.CANCELLED;
        return ordered.stream().map(x -> {
            StopProgress p = byStop.get(x.getId());
            return new StopProgressView(x.getId(), x.getStopType(), x.getSequenceNo(), x.getProvince(), x.getDistrict(), x.getWard(),
                    reveal ? x.getFullAddress() : null, reveal ? x.getContactName() : null, reveal ? x.getContactPhone() : null, reveal ? x.getNote() : null,
                    x.getLatitude(), x.getLongitude(), p == null ? StopStatus.PENDING : p.getStatus(), p == null ? null : p.getArrivedAt(),
                    p == null ? null : p.getDepartedAt(), p == null ? null : p.getNote(), tripRunning && x.getId().equals(current));
        }).toList();
    }

    static NextAction nextAction(Shipment s, List<StopProgressView> stops, StopProgressView next) {
        return switch (s.getStatus()) {
            case ASSIGNED -> NextAction.ARRIVE_PICKUP;
            case DRIVER_ARRIVED -> NextAction.CONFIRM_PICKUP;
            case PICKED_UP -> NextAction.START_TRIP;
            case IN_TRANSIT -> {
                boolean last = next == null || next.sequenceNo() == stops.getLast().sequenceNo();
                if (last) yield NextAction.DELIVER;
                yield next.status() == StopStatus.ARRIVED ? NextAction.COMPLETE_STOP : NextAction.ARRIVE_STOP;
            }
            default -> NextAction.NONE;
        };
    }

    private TripSummary summary(Shipment s, Vehicle v) {
        List<ShipmentStop> ordered = s.getStops().stream().sorted(Comparator.comparingInt(ShipmentStop::getSequenceNo)).toList();
        String pickup = ordered.stream().filter(x -> x.getStopType() == StopType.PICKUP).map(ShipmentStop::getFullAddress).findFirst().orElse(null);
        String delivery = ordered.isEmpty() ? null : ordered.getLast().getFullAddress();
        return new TripSummary(s.getId(), s.getShipmentCode(), s.getStatus(), s.getPickupTime(), s.getDeliveryDeadline(), pickup, delivery, ordered.size(),
                s.getTotalQuantity(), s.getTotalWeightKg(), s.getEstimatedPrice(), vehicle(v), incidents.countByShipmentIdAndStatusIn(s.getId(), OPEN_INCIDENTS));
    }

    private List<PhotoView> photos(Shipment s, String base) {
        List<ShipmentAttachment> rows = attachments.findByShipmentIdAndFileTypeNotOrderByCreatedAt(s.getId(), AttachmentFileType.GOODS_PHOTO);
        String prefix = base.startsWith("/api/v1/driver") ? base + s.getId() + "/photos/" : base + s.getId() + "/attachments/";
        return rows.stream().map(a -> new PhotoView(a.getId(), a.getFileType(), a.getFileName(), a.getFileSize(), a.getCreatedAt(), prefix + a.getId() + "/content")).toList();
    }

    static Milestones milestones(Shipment s) {
        return new Milestones(s.getAssignedAt(), s.getDriverArrivedAt(), s.getPickedUpAt(), s.getInTransitAt(), s.getDeliveredAt(), s.getCompletedAt());
    }

    static VehicleView vehicle(Vehicle v) { return v == null ? null : new VehicleView(v.getId(), v.getPlateNumber(), v.getVehicleType(), v.getMaxPayloadKg()); }

    private static <T> Map<UUID, T> byId(Iterable<T> rows, Function<T, UUID> id) {
        Map<UUID, T> map = new java.util.HashMap<>();
        rows.forEach(r -> map.put(id.apply(r), r));
        return map;
    }
}
