package com.truckgo.pricing.quote;

import java.util.EnumSet;
import java.util.Set;

/** Quote lifecycle, separate from the shipment status. */
public enum QuoteStatus {
    PENDING, CALCULATED, AWAITING_MANUAL_REVIEW, ACCEPTED, REJECTED, EXPIRED, SUPERSEDED;

    /** At most one quote per shipment is in one of these states (unique index in the DB). */
    public static final Set<QuoteStatus> CURRENT = EnumSet.of(PENDING, CALCULATED, AWAITING_MANUAL_REVIEW, ACCEPTED);

    public boolean isCurrent() { return CURRENT.contains(this); }
}
