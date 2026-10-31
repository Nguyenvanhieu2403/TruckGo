package com.truckgo.trip;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** OPEN -> IN_PROGRESS -> RESOLVED, or CANCELLED (reported by mistake) from either open state. */
public enum IncidentStatus {
    OPEN, IN_PROGRESS, RESOLVED, CANCELLED;

    private static final Map<IncidentStatus, Set<IncidentStatus>> NEXT = Map.of(
            OPEN, EnumSet.of(IN_PROGRESS, RESOLVED, CANCELLED),
            IN_PROGRESS, EnumSet.of(RESOLVED, CANCELLED),
            RESOLVED, EnumSet.noneOf(IncidentStatus.class),
            CANCELLED, EnumSet.noneOf(IncidentStatus.class));

    public boolean canMoveTo(IncidentStatus target) { return NEXT.get(this).contains(target); }

    public boolean isClosed() { return this == RESOLVED || this == CANCELLED; }
}
