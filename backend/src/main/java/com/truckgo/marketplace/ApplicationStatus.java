package com.truckgo.marketplace;

/** Driver application lifecycle; only PENDING changes, every other state is final. */
public enum ApplicationStatus {
    PENDING, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED;

    public boolean isActive() { return this == PENDING || this == ACCEPTED; }
}
