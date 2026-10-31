package com.truckgo.trip;

import com.truckgo.notification.NotificationRepository;
import com.truckgo.notification.NotificationService;
import com.truckgo.notification.NotificationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Puts the code in the customer's in-app notifications (visible only to them). Used until an SMS provider is
 * configured; the code is not returned by any other API. The message text holds the code only while it is valid:
 * it is overwritten when the code is used, replaced or expires (see {@link #revoke}).
 */
@Component
@ConditionalOnProperty(value = "app.trip.otp-channel", havingValue = "in-app", matchIfMissing = true)
class InAppOtpSender implements OtpSender {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    static final String REVOKED = "Mã xác nhận này đã hết hiệu lực.";
    private final NotificationService notifications;
    private final NotificationRepository repository;

    InAppOtpSender(NotificationService notifications, NotificationRepository repository) {
        this.notifications = notifications;
        this.repository = repository;
    }

    @Override public String channel() { return "in-app"; }

    @Override
    public void send(UUID customerUserId, UUID shipmentId, String shipmentCode, String code, Instant expiresAt) {
        notifications.notify(customerUserId, NotificationType.DELIVERY_OTP, "Mã xác nhận nhận hàng đơn " + shipmentCode,
                "Mã xác nhận của bạn là " + code + ", hiệu lực đến " + TIME.format(expiresAt)
                        + ". Chỉ nhập mã khi bạn đã nhận đủ hàng; không chia sẻ mã cho người khác.", "SHIPMENT", shipmentId);
    }

    /** The code must not stay readable in the inbox (or the database) once it is used, replaced or expired. */
    @Override
    public void revoke(UUID shipmentId) { repository.redact(NotificationType.DELIVERY_OTP.name(), shipmentId, REVOKED); }
}
