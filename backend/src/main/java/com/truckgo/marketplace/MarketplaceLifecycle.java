package com.truckgo.marketplace;

import com.truckgo.driver.DriverProfile;
import com.truckgo.notification.NotificationService;
import com.truckgo.notification.NotificationType;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentCancelledEvent;
import com.truckgo.shipment.ShipmentDeliveredEvent;
import com.truckgo.shipment.ShipmentListingChangedEvent;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.shipment.ShipmentStatusHistory;
import com.truckgo.shipment.ShipmentStatusHistoryRepository;
import com.truckgo.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Closes applications and releases assignments when their order is cancelled or did not get every driver it needs
 * before pickup, and frees drivers and vehicles once a trip is delivered.
 */
@Component
public class MarketplaceLifecycle {
    static final String EXPIRED_REASON = "Đơn đã hết hạn nhận tài xế";
    /** Name recorded as the actor of changes made by the expiry job (shipment_status_histories.system_actor). */
    public static final String EXPIRY_ACTOR = "marketplace-expiry-job";
    private static final Logger log = LoggerFactory.getLogger(MarketplaceLifecycle.class);

    private final DriverShipmentApplicationRepository applications;
    private final ShipmentRepository shipments;
    private final ShipmentStatusHistoryRepository statusHistory;
    private final MarketplaceViews views;
    private final AssignmentService assignments;
    private final NotificationService notifications;
    private final MarketplaceCache cache;
    private final Clock clock;

    public MarketplaceLifecycle(DriverShipmentApplicationRepository applications, ShipmentRepository shipments, ShipmentStatusHistoryRepository statusHistory,
                                MarketplaceViews views, AssignmentService assignments, NotificationService notifications,
                                MarketplaceCache cache, Clock clock) {
        this.applications = applications;
        this.shipments = shipments;
        this.statusHistory = statusHistory;
        this.views = views;
        this.assignments = assignments;
        this.notifications = notifications;
        this.cache = cache;
        this.clock = clock;
    }

    /** A newly published or newly priced order must show up for drivers immediately, not after the cache TTL. */
    @EventListener
    public void onListingChanged(ShipmentListingChangedEvent event) { cache.invalidateAfterCommit(); }

    /** Runs inside the cancel transaction (synchronous listener), so the order and its applications close together. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onShipmentCancelled(ShipmentCancelledEvent event) {
        Shipment shipment = shipments.findById(event.shipmentId()).orElseThrow();
        int closed = closePending(shipment, "Khách hàng đã hủy đơn");
        int released = assignments.releaseAll(shipment, AssignmentService.Actor.user(event.customerId()), "Khách hàng đã hủy đơn");
        cache.invalidateAfterCommit();
        if (closed + released > 0) log.info("Closed {} applications and {} assignments of cancelled shipment {}", closed, released, event.shipmentId());
    }

    /** A delivered trip no longer occupies its drivers and vehicles. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onDelivered(ShipmentDeliveredEvent event) {
        int finished = assignments.finishAll(event.shipmentId());
        if (finished > 0) log.info("Finished {} assignments of delivered shipment {}", finished, event.shipmentId());
    }

    /**
     * Expires one open order whose pickup time has passed. Each order runs in its own transaction under the row
     * lock, so a customer accepting at the same moment either wins (order no longer PUBLISHED, skipped) or loses.
     */
    @Transactional
    public boolean expireIfDue(UUID shipmentId) {
        Shipment shipment = shipments.findByIdForUpdate(shipmentId).orElse(null);
        Instant now = clock.instant();
        if (shipment == null || shipment.getStatus() != ShipmentStatus.PUBLISHED || shipment.isAssigned()
                || shipment.getPickupTime() == null || shipment.getPickupTime().isAfter(now)) return false;
        boolean partial = assignments.releaseAll(shipment, AssignmentService.Actor.system(EXPIRY_ACTOR), "Đơn hết hạn khi chưa đủ tài xế") > 0;
        shipment.markExpired();
        shipments.flush();
        // Nobody acts here: recorded as the expiry job, not as any user.
        statusHistory.save(ShipmentStatusHistory.system(shipmentId, ShipmentStatus.PUBLISHED, ShipmentStatus.EXPIRED, EXPIRY_ACTOR,
                partial ? "Quá giờ lấy hàng mà chưa đủ " + shipment.getVehicleCount() + " tài xế" : "Quá giờ lấy hàng mà chưa chọn tài xế"));
        int closed = closePending(shipment, EXPIRED_REASON);
        notifications.notify(shipment.getCustomerId(), NotificationType.SHIPMENT_EXPIRED, "Đơn " + shipment.getShipmentCode() + " đã hết hạn",
                "Đơn " + shipment.getShipmentCode() + (partial ? " đã quá giờ lấy hàng mà chưa đủ tài xế nên đã hết hạn."
                        : " đã quá giờ lấy hàng mà chưa chọn tài xế nên đã hết hạn."), "SHIPMENT", shipmentId, "SHIPMENT_EXPIRED:" + shipmentId);
        cache.invalidateAfterCommit();
        log.info("Shipment expired shipment={} closedApplications={} releasedPartialAssignments={}", shipmentId, closed, partial);
        return true;
    }

    private int closePending(Shipment shipment, String reason) {
        Instant now = clock.instant();
        List<DriverShipmentApplication> pending = applications.findByShipmentIdAndStatus(shipment.getId(), ApplicationStatus.PENDING);
        pending.forEach(a -> a.expire(now, reason));
        applications.flush();
        Map<UUID, DriverProfile> drivers = views.drivers(pending.stream().map(DriverShipmentApplication::getDriverId).toList());
        Map<UUID, User> users = views.driverUsers(drivers.values());
        pending.forEach(a -> assignments.notifyClosed(users.get(a.getDriverId()), NotificationType.APPLICATION_EXPIRED, shipment, a, reason));
        return pending.size();
    }
}
