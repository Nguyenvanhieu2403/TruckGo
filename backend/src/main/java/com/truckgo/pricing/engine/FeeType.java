package com.truckgo.pricing.engine;

/**
 * FIXED: amount once. PERCENTAGE: percent of the transport charge (base + distance). PER_UNIT: amount × the service's
 * numeric value (e.g. floors) or × total parcels. PER_STOP: amount × number of stops. FREE: nothing.
 */
public enum FeeType { FIXED, PERCENTAGE, PER_UNIT, PER_STOP, FREE }
