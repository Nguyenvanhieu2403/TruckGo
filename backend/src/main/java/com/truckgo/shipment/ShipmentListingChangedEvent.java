package com.truckgo.shipment;

import java.util.UUID;

/**
 * Something that decides whether an order is open to drivers changed (published, price accepted). Listeners such as
 * the marketplace cache react without the publishing module depending on them.
 */
public record ShipmentListingChangedEvent(UUID shipmentId) { }
