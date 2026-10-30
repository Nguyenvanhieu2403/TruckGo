package com.truckgo.shipment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Full shipment lifecycle. Sprint 3 only performs DRAFT -> PUBLISHED and DRAFT/PUBLISHED -> CANCELLED; the other
 * states are defined now so later sprints share one model. Every change goes through {@link #canTransitionTo}.
 */
public enum ShipmentStatus {
    DRAFT, PUBLISHED, ASSIGNED, DRIVER_ARRIVED, PICKED_UP, IN_TRANSIT, DELIVERED, COMPLETED, CANCELLED, EXPIRED;

    private static final Map<ShipmentStatus, Set<ShipmentStatus>> TRANSITIONS = Map.of(
            DRAFT, EnumSet.of(PUBLISHED, CANCELLED),
            PUBLISHED, EnumSet.of(ASSIGNED, CANCELLED, EXPIRED),
            ASSIGNED, EnumSet.of(DRIVER_ARRIVED, CANCELLED),
            DRIVER_ARRIVED, EnumSet.of(PICKED_UP, CANCELLED),
            PICKED_UP, EnumSet.of(IN_TRANSIT),
            IN_TRANSIT, EnumSet.of(DELIVERED),
            DELIVERED, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.noneOf(ShipmentStatus.class),
            CANCELLED, EnumSet.noneOf(ShipmentStatus.class),
            EXPIRED, EnumSet.noneOf(ShipmentStatus.class));

    public boolean canTransitionTo(ShipmentStatus target) { return TRANSITIONS.get(this).contains(target); }

    /** A customer may cancel only before a driver has accepted the order. */
    public boolean isCancellableByCustomer() { return this == DRAFT || this == PUBLISHED; }

    public boolean isEditable() { return this == DRAFT; }
}
