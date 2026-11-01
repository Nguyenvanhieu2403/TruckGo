package com.truckgo.marketplace;

/** ACTIVE blocks the driver's and vehicle's schedule; RELEASED (removed, replaced, order cancelled/expired) and FINISHED (delivered) do not. */
public enum AssignmentStatus { ACTIVE, RELEASED, FINISHED }
