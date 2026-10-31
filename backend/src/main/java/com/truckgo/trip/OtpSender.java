package com.truckgo.trip;

import java.time.Instant;
import java.util.UUID;

/**
 * Delivers a delivery-confirmation code to the customer. The default implementation uses the in-app notification
 * inbox; an SMS/Zalo/e-mail adapter can replace it (app.trip.otp-channel) without touching the confirmation flow.
 * Implementations must never log the code.
 */
public interface OtpSender {
    String channel();

    void send(UUID customerUserId, UUID shipmentId, String shipmentCode, String code, Instant expiresAt);

    /** Makes previously sent codes for the order unreadable where the channel allows it (e.g. in-app messages). */
    default void revoke(UUID shipmentId) { }
}
