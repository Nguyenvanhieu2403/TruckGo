package com.truckgo.shipment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * app.shipment.*: businessZone interprets date filters and code dates; minPickupLead is the minimum gap between
 * publishing and the pickup time.
 */
@ConfigurationProperties("app.shipment")
public record ShipmentProperties(ZoneId businessZone, Duration minPickupLead, int maxStops, int maxItems,
                                 int maxAttachments, DataSize maxAttachmentSize, List<String> allowedImageTypes) { }
