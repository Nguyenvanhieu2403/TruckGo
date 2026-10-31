package com.truckgo.trip;

import com.truckgo.shipment.ShipmentCancelledEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Ends location sharing when an order is cancelled and hides delivery codes that expired unused. */
@Component
class TripLifecycle {
    private final TrackingService tracking;
    private final DeliveryConfirmationRepository confirmations;
    private final OtpSender sender;
    private final Clock clock;

    TripLifecycle(TrackingService tracking, DeliveryConfirmationRepository confirmations, OtpSender sender, Clock clock) {
        this.tracking = tracking;
        this.confirmations = confirmations;
        this.sender = sender;
        this.clock = clock;
    }

    @EventListener
    public void onCancelled(ShipmentCancelledEvent event) { tracking.endSession(event.shipmentId(), clock.instant()); }

    @Scheduled(fixedDelayString = "${app.trip.otp-cleanup-interval:5m}", initialDelayString = "${app.trip.otp-cleanup-interval:5m}")
    @Transactional
    public void revokeExpiredCodes() {
        for (UUID shipmentId : confirmations.findShipmentsWithExpiredPending(clock.instant())) sender.revoke(shipmentId);
    }
}
