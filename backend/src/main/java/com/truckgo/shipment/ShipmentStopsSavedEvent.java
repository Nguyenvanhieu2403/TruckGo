package com.truckgo.shipment;

import java.util.UUID;

/** A draft's stops were written; listeners (e.g. coordinate filling) run after the transaction commits. */
public record ShipmentStopsSavedEvent(UUID shipmentId) { }
