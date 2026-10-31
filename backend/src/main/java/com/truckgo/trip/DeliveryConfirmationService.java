package com.truckgo.trip;

import com.truckgo.common.exception.BusinessRuleException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.driver.DriverProfileRepository;
import com.truckgo.notification.NotificationService;
import com.truckgo.notification.NotificationType;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.shipment.ShipmentStatusHistory;
import com.truckgo.shipment.ShipmentStatusHistoryRepository;
import com.truckgo.trip.dto.TripDtos.ConfirmationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Delivery confirmation by one-time code. The code is generated with SecureRandom when the driver marks the order
 * delivered, sent through {@link OtpSender} and stored only as an HMAC. It expires after app.trip.otp-ttl, allows
 * app.trip.otp-max-attempts wrong guesses (then the code is locked and a new one must be requested), and a new code
 * can be requested after app.trip.otp-resend-cooldown, at most app.trip.otp-max-sends times. No API returns the code.
 */
@Service
public class DeliveryConfirmationService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryConfirmationService.class);

    private final DeliveryConfirmationRepository confirmations;
    private final ShipmentRepository shipments;
    private final ShipmentStatusHistoryRepository statusHistory;
    private final DriverProfileRepository drivers;
    private final OtpCodec codec;
    private final OtpSender sender;
    private final NotificationService notifications;
    private final TrackingService tracking;
    private final TripEventHub events;
    private final TripProperties properties;
    private final Clock clock;

    public DeliveryConfirmationService(DeliveryConfirmationRepository confirmations, ShipmentRepository shipments, ShipmentStatusHistoryRepository statusHistory,
                                       DriverProfileRepository drivers, OtpCodec codec, OtpSender sender, NotificationService notifications,
                                       TrackingService tracking, TripEventHub events, TripProperties properties, Clock clock) {
        this.confirmations = confirmations;
        this.shipments = shipments;
        this.statusHistory = statusHistory;
        this.drivers = drivers;
        this.codec = codec;
        this.sender = sender;
        this.notifications = notifications;
        this.tracking = tracking;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** Called inside the deliver transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(Shipment shipment) {
        Instant now = clock.instant();
        String code = codec.newCode();
        DeliveryConfirmation confirmation = new DeliveryConfirmation(shipment.getId(), "pending", now.plus(properties.otpTtl()), properties.otpMaxAttempts(), now);
        // The hash is bound to the row id, which only exists once the object is built.
        confirmation = confirmations.saveAndFlush(withHash(confirmation, code));
        sender.send(shipment.getCustomerId(), shipment.getId(), shipment.getShipmentCode(), code, confirmation.getExpiresAt());
        log.info("Delivery confirmation code issued shipment={} channel={}", shipment.getId(), sender.channel());
    }

    @Transactional
    public ConfirmationView resend(UUID customerId, UUID shipmentId) {
        Shipment shipment = lockOwned(customerId, shipmentId);
        if (shipment.getStatus() != ShipmentStatus.DELIVERED) throw TripErrors.status(ShipmentStatus.DELIVERED.name(), shipment.getStatus());
        DeliveryConfirmation c = confirmations.findFirstByShipmentIdOrderByCreatedAtDesc(shipmentId).orElseThrow(() -> new ResourceNotFoundException("Chưa có mã xác nhận"));
        Instant now = clock.instant();
        Instant available = c.getLastSentAt().plus(properties.otpResendCooldown());
        if (now.isBefore(available))
            throw new BusinessRuleException(HttpStatus.TOO_MANY_REQUESTS, TripErrors.OTP_RESEND_TOO_SOON, "Vui lòng chờ trước khi yêu cầu mã mới",
                    Map.of("retryAfterSeconds", Math.max(Duration.between(now, available).toSeconds(), 1)));
        if (c.getSendCount() >= properties.otpMaxSends())
            throw BusinessRuleException.conflict(TripErrors.OTP_RESEND_LIMIT, "Đã gửi lại mã quá số lần cho phép, vui lòng liên hệ hỗ trợ");
        String code = codec.newCode();
        c.reissue(codec.hash(c.getId(), code), now.plus(properties.otpTtl()), now);
        confirmations.flush();
        sender.revoke(shipmentId);
        sender.send(shipment.getCustomerId(), shipment.getId(), shipment.getShipmentCode(), code, c.getExpiresAt());
        return view(c, now);
    }

    /**
     * DELIVERED -> COMPLETED when the code matches. Wrong guesses are committed even though the request fails, so the
     * attempt counter cannot be reset by retrying.
     */
    @Transactional(noRollbackFor = BusinessRuleException.class)
    public void confirm(UUID customerId, UUID shipmentId, String otp, String note) {
        Shipment shipment = lockOwned(customerId, shipmentId);
        if (shipment.getStatus() != ShipmentStatus.DELIVERED) throw TripErrors.status(ShipmentStatus.DELIVERED.name(), shipment.getStatus());
        DeliveryConfirmation c = confirmations.findFirstByShipmentIdOrderByCreatedAtDesc(shipmentId).orElseThrow(() -> new ResourceNotFoundException("Chưa có mã xác nhận"));
        Instant now = clock.instant();
        if (c.getStatus() == DeliveryConfirmation.Status.LOCKED)
            throw BusinessRuleException.conflict(TripErrors.OTP_LOCKED, "Đã nhập sai quá số lần cho phép, vui lòng yêu cầu mã mới");
        if (c.isExpiredAt(now)) throw BusinessRuleException.conflict(TripErrors.OTP_EXPIRED, "Mã xác nhận đã hết hạn, vui lòng yêu cầu mã mới");
        if (!codec.matches(c.getId(), otp, c.getOtpHash())) {
            boolean locked = c.fail();
            confirmations.flush();
            if (locked) throw BusinessRuleException.conflict(TripErrors.OTP_LOCKED, "Đã nhập sai quá số lần cho phép, vui lòng yêu cầu mã mới");
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, TripErrors.OTP_INVALID, "Mã xác nhận không đúng",
                    Map.of("attemptsLeft", c.attemptsLeft()));
        }
        String cleanNote = TripTransitionService.clean(note);
        c.confirm(now, customerId, cleanNote);
        shipment.markCompleted(now);
        shipments.flush();
        confirmations.flush();
        tracking.endSession(shipmentId, now);
        statusHistory.save(new ShipmentStatusHistory(shipmentId, ShipmentStatus.DELIVERED, ShipmentStatus.COMPLETED, customerId,
                cleanNote == null ? "Khách hàng xác nhận đã nhận hàng" : "Khách hàng xác nhận đã nhận hàng: " + cleanNote));
        sender.revoke(shipmentId);
        drivers.findById(shipment.getAssignedDriverId()).ifPresent(d -> notifications.notify(d.getUserId(), NotificationType.DELIVERY_CONFIRMED,
                "Đơn " + shipment.getShipmentCode() + " đã hoàn thành", "Khách hàng đã xác nhận nhận hàng đơn " + shipment.getShipmentCode() + ".", "SHIPMENT", shipmentId));
        events.publishStatus(shipment);
        log.info("Delivery confirmed shipment={}", shipmentId);
    }

    @Transactional(readOnly = true)
    public Optional<ConfirmationView> current(UUID shipmentId) {
        return confirmations.findTopByShipmentIdOrderByCreatedAtDesc(shipmentId).map(c -> view(c, clock.instant()));
    }

    private ConfirmationView view(DeliveryConfirmation c, Instant now) {
        boolean pending = c.getStatus() == DeliveryConfirmation.Status.PENDING && !c.isExpiredAt(now);
        Instant resendAt = c.getSendCount() >= properties.otpMaxSends() ? null : c.getLastSentAt().plus(properties.otpResendCooldown());
        return new ConfirmationView(pending, c.getExpiresAt(), c.attemptsLeft(), resendAt, sender.channel(), c.getStatus() == DeliveryConfirmation.Status.LOCKED);
    }

    private DeliveryConfirmation withHash(DeliveryConfirmation c, String code) {
        c.reissueInitial(codec.hash(c.getId(), code));
        return c;
    }

    private Shipment lockOwned(UUID customerId, UUID shipmentId) {
        return shipments.findByIdForUpdate(shipmentId).filter(s -> s.isOwnedBy(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn vận chuyển"));
    }
}
