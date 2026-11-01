package com.truckgo.vehicle;

/** Codes of the vehicle_body_types catalog the application has rules for. */
public final class BodyTypes {
    public static final String ANY = "ANY";
    public static final String REFRIGERATED = "REFRIGERATED";
    public static final String CONTAINER = "CONTAINER";

    private BodyTypes() { }

    /** The body a vehicle type implies, or null when any body is possible. */
    public static String requiredFor(VehicleType type) {
        return switch (type) {
            case REFRIGERATED_TRUCK -> REFRIGERATED;
            case CONTAINER_TRUCK -> CONTAINER;
            default -> null;
        };
    }
}
