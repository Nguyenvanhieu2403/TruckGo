package com.truckgo.marketplace;

/**
 * Driver application lifecycle. PENDING is answered once; ACCEPTED can still become RELEASED when the customer or an
 * administrator removes the driver before the trip starts. Every other state is final.
 */
public enum ApplicationStatus {
    PENDING, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED, RELEASED;

    public boolean isActive() { return this == PENDING || this == ACCEPTED; }
}
