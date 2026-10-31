package com.truckgo.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.common.exception.BusinessRuleException;
import com.truckgo.common.response.PageResponse;
import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverProfileRepository;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.trip.dto.TripDtos.LocationAck;
import com.truckgo.trip.dto.TripDtos.LocationRequest;
import com.truckgo.trip.dto.TripDtos.TrackingPoint;
import com.truckgo.trip.dto.TripDtos.TrackingPolicy;
import com.truckgo.trip.dto.TripDtos.TrackingState;
import com.truckgo.trip.dto.TripDtos.TrackingView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * GPS for trips in transit. PostgreSQL keeps every accepted fix (history, retention-limited); Redis keeps only the
 * latest fix per trip and the per-trip rate limit, with PostgreSQL as the fallback for both.
 *
 * <p>Client fixes are not trusted blindly: the device time must be close to the server's, fixes must arrive in order,
 * duplicates are ignored, and a jump implying an impossible speed from the previous fix is refused.
 */
@Service
public class TrackingService {
    static final String LATEST_KEY = "truckgo:trip:%s:latest-location";
    static final String RATE_KEY = "truckgo:trip:%s:gps-rate";
    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);

    record Latest(BigDecimal latitude, BigDecimal longitude, BigDecimal accuracy, BigDecimal speed, BigDecimal heading, Instant recordedAt, Instant receivedAt) { }

    private final TrackingLocationRepository locations;
    private final TrackingSessionRepository sessions;
    private final ShipmentRepository shipments;
    private final DriverProfileRepository drivers;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final TripEventHub events;
    private final TrackingProperties properties;
    private final Clock clock;

    public TrackingService(TrackingLocationRepository locations, TrackingSessionRepository sessions, ShipmentRepository shipments,
                           DriverProfileRepository drivers, StringRedisTemplate redis, ObjectMapper json, TripEventHub events,
                           TrackingProperties properties, Clock clock) {
        this.locations = locations;
        this.sessions = sessions;
        this.shipments = shipments;
        this.drivers = drivers;
        this.redis = redis;
        this.json = json;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    public TrackingPolicy policy() {
        return new TrackingPolicy(properties.movingInterval().toSeconds(), properties.idleInterval().toSeconds(), properties.minDistanceMeters());
    }

    // ------------------------------------------------------------------ driver side

    @Transactional
    public LocationAck record(UUID userId, UUID shipmentId, LocationRequest request) {
        DriverProfile driver = drivers.findByUserId(userId).orElseThrow(TripTransitionService::notFound);
        Shipment shipment = shipments.findById(shipmentId).filter(s -> s.isAssignedTo(driver.getId())).orElseThrow(TripTransitionService::notFound);
        if (shipment.getStatus() != ShipmentStatus.IN_TRANSIT)
            throw BusinessRuleException.conflict(TripErrors.TRACKING_NOT_ACTIVE, "Chỉ chia sẻ vị trí khi chuyến đang vận chuyển");
        TrackingSession session = sessions.findById(shipmentId).orElseThrow(() ->
                BusinessRuleException.conflict(TripErrors.TRACKING_NOT_ACTIVE, "Chuyến chưa bắt đầu chia sẻ vị trí"));
        if (session.getStatus() != TrackingSession.Status.ACTIVE)
            throw BusinessRuleException.conflict(TripErrors.TRACKING_NOT_ACTIVE, "Bạn đang tạm dừng chia sẻ vị trí");

        Instant now = clock.instant();
        Instant recordedAt = request.recordedAt().toInstant();
        if (recordedAt.isAfter(now.plus(properties.maxFutureSkew())) || recordedAt.isBefore(now.minus(properties.maxAge())))
            throw TripErrors.unprocessable(TripErrors.LOCATION_INVALID_TIME, "Thời điểm ghi nhận vị trí không hợp lệ, hãy kiểm tra đồng hồ thiết bị");
        GeoFix fix = new GeoFix(request.latitude(), request.longitude());

        Optional<Latest> previous = latest(shipmentId);
        if (previous.isPresent()) {
            Latest last = previous.get();
            if (recordedAt.equals(last.recordedAt())) return ack(false, "DUPLICATE", recordedAt);
            if (recordedAt.isBefore(last.recordedAt())) return ack(false, "OUT_OF_ORDER", recordedAt);
            double seconds = Math.max(Duration.between(last.recordedAt(), recordedAt).toMillis() / 1000.0, 1);
            double kmh = fix.metersTo(new GeoFix(last.latitude(), last.longitude())) / seconds * 3.6;
            if (kmh > properties.maxSpeedKmh())
                throw TripErrors.unprocessable(TripErrors.LOCATION_IMPLAUSIBLE, "Vị trí thay đổi bất thường so với lần gửi trước");
        }
        rateLimit(shipmentId, previous, now);

        TrackingLocation saved;
        try {
            saved = locations.saveAndFlush(new TrackingLocation(shipmentId, driver.getId(), fix, request.accuracy(), request.speed(), request.heading(), recordedAt, now));
        } catch (DataIntegrityViolationException ex) {
            return ack(false, "DUPLICATE", recordedAt);   // same (shipment, recordedAt) arrived twice at once
        }
        Latest latest = new Latest(saved.getLatitude(), saved.getLongitude(), saved.getAccuracy(), saved.getSpeed(), saved.getHeading(), recordedAt, now);
        cacheLatest(shipmentId, latest);
        events.publishLocation(shipmentId, view(shipment, Optional.of(latest), Optional.of(session)));
        return ack(true, null, recordedAt);
    }

    @Transactional
    public TrackingView pause(UUID userId, UUID shipmentId, boolean pause) {
        DriverProfile driver = drivers.findByUserId(userId).orElseThrow(TripTransitionService::notFound);
        Shipment shipment = shipments.findById(shipmentId).filter(s -> s.isAssignedTo(driver.getId())).orElseThrow(TripTransitionService::notFound);
        TrackingSession session = sessions.findById(shipmentId)
                .filter(s -> s.getStatus() != TrackingSession.Status.ENDED && shipment.getStatus() == ShipmentStatus.IN_TRANSIT)
                .orElseThrow(() -> BusinessRuleException.conflict(TripErrors.TRACKING_NOT_ACTIVE, "Chuyến không ở trạng thái chia sẻ vị trí"));
        if (pause) session.pause(clock.instant()); else session.resume();
        sessions.flush();
        TrackingView view = view(shipment, latest(shipmentId), Optional.of(session));
        events.publishLocation(shipmentId, view);
        return view;
    }

    void startSession(Shipment shipment, Instant at) {
        if (sessions.existsById(shipment.getId())) return;
        sessions.saveAndFlush(new TrackingSession(shipment.getId(), shipment.getAssignedDriverId(), at));
    }

    /** No more fixes are accepted; the latest point is dropped from Redis so it can never be shown as current. */
    void endSession(UUID shipmentId, Instant at) {
        sessions.findById(shipmentId).ifPresent(s -> { s.end(at); sessions.flush(); });
        try { redis.delete(LATEST_KEY.formatted(shipmentId)); } catch (RuntimeException ex) { log.warn("Could not drop latest location from Redis: {}", ex.getClass().getSimpleName()); }
    }

    // ------------------------------------------------------------------ viewer side

    @Transactional(readOnly = true)
    public TrackingView current(Shipment shipment) {
        return view(shipment, latest(shipment.getId()), sessions.findById(shipment.getId()));
    }

    @Transactional(readOnly = true)
    public PageResponse<TrackingPoint> history(UUID shipmentId, Instant from, Instant to, int page, int size) {
        Instant start = from == null ? Instant.EPOCH : from;
        Instant end = to == null ? clock.instant().plus(properties.maxFutureSkew()) : to;
        if (end.isBefore(start)) throw new IllegalArgumentException("Khoảng thời gian không hợp lệ");
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500), Sort.by(Sort.Direction.ASC, "recordedAt"));
        return PageResponse.of(locations.findByShipmentIdAndRecordedAtBetween(shipmentId, start, end, pageable),
                l -> new TrackingPoint(l.getLatitude(), l.getLongitude(), l.getAccuracy(), l.getSpeed(), l.getHeading(), l.getRecordedAt()));
    }

    /**
     * LIVE/STALE/OFFLINE by the age of the latest fix; coordinates only while in transit and never once the session
     * ended, so an old position is never presented as the driver's current one.
     */
    TrackingView view(Shipment shipment, Optional<Latest> latest, Optional<TrackingSession> session) {
        TrackingSession.Status sessionStatus = session.map(TrackingSession::getStatus).orElse(null);
        if (shipment.getStatus() != ShipmentStatus.IN_TRANSIT || sessionStatus == null || sessionStatus == TrackingSession.Status.ENDED) {
            TrackingState state = sessionStatus == null && isBeforeTransit(shipment.getStatus()) ? TrackingState.NOT_STARTED : TrackingState.ENDED;
            return new TrackingView(state, sessionStatus, null, null, null, null, null, null, null);
        }
        if (latest.isEmpty()) return new TrackingView(sessionStatus == TrackingSession.Status.PAUSED ? TrackingState.PAUSED : TrackingState.OFFLINE,
                sessionStatus, null, null, null, null, null, null, null);
        Latest l = latest.get();
        long age = Math.max(Duration.between(l.recordedAt(), clock.instant()).toSeconds(), 0);
        TrackingState state = sessionStatus == TrackingSession.Status.PAUSED ? TrackingState.PAUSED
                : age <= properties.liveThreshold().toSeconds() ? TrackingState.LIVE
                : age <= properties.staleThreshold().toSeconds() ? TrackingState.STALE : TrackingState.OFFLINE;
        if (state == TrackingState.OFFLINE || state == TrackingState.PAUSED)
            // Show where the driver was last seen, clearly marked by state and age, but no speed/heading.
            return new TrackingView(state, sessionStatus, l.latitude(), l.longitude(), l.accuracy(), null, null, l.recordedAt(), age);
        return new TrackingView(state, sessionStatus, l.latitude(), l.longitude(), l.accuracy(), l.speed(), l.heading(), l.recordedAt(), age);
    }

    private static boolean isBeforeTransit(ShipmentStatus status) {
        return status == ShipmentStatus.ASSIGNED || status == ShipmentStatus.DRIVER_ARRIVED || status == ShipmentStatus.PICKED_UP;
    }

    // ------------------------------------------------------------------ storage helpers

    Optional<Latest> latest(UUID shipmentId) {
        try {
            String raw = redis.opsForValue().get(LATEST_KEY.formatted(shipmentId));
            if (raw != null) return Optional.of(json.readValue(raw, Latest.class));
        } catch (Exception ex) {
            log.warn("Latest location read from Redis failed, using database: {}", ex.getClass().getSimpleName());
        }
        return locations.findFirstByShipmentIdOrderByRecordedAtDesc(shipmentId)
                .map(l -> new Latest(l.getLatitude(), l.getLongitude(), l.getAccuracy(), l.getSpeed(), l.getHeading(), l.getRecordedAt(), l.getReceivedAt()));
    }

    private void cacheLatest(UUID shipmentId, Latest latest) {
        try { redis.opsForValue().set(LATEST_KEY.formatted(shipmentId), json.writeValueAsString(latest), properties.latestTtl()); }
        catch (Exception ex) { log.warn("Latest location write to Redis failed: {}", ex.getClass().getSimpleName()); }
    }

    /** At most one accepted fix per trip per minInterval: Redis SET NX PX, or the last received time when Redis is down. */
    private void rateLimit(UUID shipmentId, Optional<Latest> previous, Instant now) {
        Boolean first;
        try {
            first = redis.opsForValue().setIfAbsent(RATE_KEY.formatted(shipmentId), "1", properties.minInterval());
        } catch (RuntimeException ex) {
            first = previous.map(p -> Duration.between(p.receivedAt(), now).compareTo(properties.minInterval()) >= 0).orElse(true);
        }
        if (!Boolean.TRUE.equals(first))
            throw new BusinessRuleException(HttpStatus.TOO_MANY_REQUESTS, TripErrors.LOCATION_RATE_LIMITED, "Gửi vị trí quá dày, vui lòng giảm tần suất",
                    Map.of("retryAfterSeconds", Math.max(properties.minInterval().toSeconds(), 1)));
    }

    private LocationAck ack(boolean accepted, String reason, Instant recordedAt) { return new LocationAck(accepted, reason, recordedAt, policy()); }

    /** Retention: fixes of finished trips are not kept forever. */
    @Scheduled(cron = "${app.tracking.retention-cron:0 30 3 * * *}")
    @Transactional
    public void purgeOld() {
        int deleted = locations.deleteFinishedBefore(clock.instant().minus(properties.retention()));
        if (deleted > 0) log.info("Tracking retention deleted {} fixes", deleted);
    }
}
