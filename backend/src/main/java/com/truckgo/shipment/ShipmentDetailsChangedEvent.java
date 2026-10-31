package com.truckgo.shipment;

import java.util.UUID;

/** Published inside the transaction that changed an order's stops, cargo, vehicle request or services. */
public record ShipmentDetailsChangedEvent(UUID shipmentId, UUID changedBy) { }
