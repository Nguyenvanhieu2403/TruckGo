package com.truckgo.shipment;

import java.util.UUID;

/** Published inside the cancelling transaction so open driver applications are closed atomically with the order. */
public record ShipmentCancelledEvent(UUID shipmentId, UUID customerId, String shipmentCode, String reason) { }
