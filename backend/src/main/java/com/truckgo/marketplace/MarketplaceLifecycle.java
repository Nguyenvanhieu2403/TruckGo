package com.truckgo.marketplace;

import com.truckgo.driver.DriverProfile;
import com.truckgo.notification.NotificationService;
import com.truckgo.notification.NotificationType;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentCancelledEvent;
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

/** Closes applications when their order is cancelled or nobody was chosen before pickup. */
@Component
public class MarketplaceLifecycle {
    static final String EXPIRED_REASON = "Đơn đã hết hạn nhận tài xế";
    private static final Logger log = LoggerFactory.getLogger(MarketplaceLifecycle.class);

    private final DriverShipmentApplicationRepository applications;
    private final ShipmentRepository shipments;
    private final ShipmentStatusHistoryRepository statusHistory;
    private final MarketplaceViews views;
    private final CustomerApplicationService customerApplications;
    private final NotificationService notifications;
    private final MarketplaceCache cache;
    private final Clock clock;

    public MarketplaceLifecycle(DriverShipmentApplicationRepository applications, ShipmentRepository shipments, ShipmentStatusHistoryRepository statusHistory,
                                MarketplaceViews views, CustomerApplicationService customerApplications, NotificationService notifications,
                                MarketplaceCache cache, Clock clock) {
        this.applications = applications;
        this.shipments = shipments;
        this.statusHistory = statusHistory;
        this.views = views;
        this.customerApplications = customerApplications;
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
        cache.invalidateAfterCommit();
        if (closed > 0) log.info("Closed {} applications of cancelled shipment {}", closed, event.shipmentId());
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
        shipment.markExpired();
        shipments.flush();
        // Nobody acts here; the order's owner is recorded with an explicit system reason (changed_by is mandatory).
        statusHistory.save(new ShipmentStatusHistory(shipmentId, ShipmentStatus.PUBLISHED, ShipmentStatus.EXPIRED, shipment.getCustomerId(),
                "Hệ thống: quá giờ lấy hàng mà chưa chọn tài xế"));
        int closed = closePending(shipment, EXPIRED_REASON);
        notifications.notify(shipment.getCustomerId(), NotificationType.SHIPMENT_EXPIRED, "Đơn " + shipment.getShipmentCode() + " đã hết hạn",
                "Đơn " + shipment.getShipmentCode() + " đã quá giờ lấy hàng mà chưa chọn tài xế nên đã hết hạn.", "SHIPMENT", shipmentId);
        cache.invalidateAfterCommit();
        log.info("Shipment expired shipment={} closedApplications={}", shipmentId, closed);
        return true;
    }

    private int closePending(Shipment shipment, String reason) {
        Instant now = clock.instant();
        List<DriverShipmentApplication> pending = applications.findByShipmentIdAndStatus(shipment.getId(), ApplicationStatus.PENDING);
        pending.forEach(a -> a.expire(now, reason));
        applications.flush();
        Map<UUID, DriverProfile> drivers = views.drivers(pending.stream().map(DriverShipmentApplication::getDriverId).toList());
        Map<UUID, User> users = views.driverUsers(drivers.values());
        pending.forEach(a -> customerApplications.notifyClosed(users.get(a.getDriverId()), NotificationType.APPLICATION_EXPIRED, shipment, a, reason));
        return pending.size();
    }
}
