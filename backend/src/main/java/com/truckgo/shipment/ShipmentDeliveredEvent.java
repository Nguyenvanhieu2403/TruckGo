package com.truckgo.shipment;

import java.util.UUID;

/** Published inside the transaction that marks an order DELIVERED; listeners free what the trip occupied. */
public record ShipmentDeliveredEvent(UUID shipmentId) { }
