package com.truckgo.pricing.quote;

import com.truckgo.catalog.CatalogService;
import com.truckgo.catalog.dto.CatalogDtos.VehicleTypeDto;
import com.truckgo.pricing.dto.QuoteDtos.AdminQuoteResponse;
import com.truckgo.pricing.dto.QuoteDtos.Breakdown;
import com.truckgo.pricing.dto.QuoteDtos.LineResponse;
import com.truckgo.pricing.dto.QuoteDtos.QuoteActions;
import com.truckgo.pricing.dto.QuoteDtos.QuoteResponse;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.shipment.ShipmentStop;
import com.truckgo.shipment.StopType;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class QuoteMapper {
    private final CatalogService catalogs;
    private final Clock clock;

    public QuoteMapper(CatalogService catalogs, Clock clock) {
        this.catalogs = catalogs;
        this.clock = clock;
    }

    public QuoteResponse toResponse(ShipmentQuote q, Shipment s, boolean viewerIsOwner) {
        boolean live = q.getStatus() == QuoteStatus.CALCULATED && !q.isExpiredAt(clock.instant());
        boolean shipmentOpen = s != null && (s.getStatus() == ShipmentStatus.DRAFT || s.getStatus() == ShipmentStatus.PUBLISHED);
        Breakdown breakdown = q.getTotalPrice() == null ? null : new Breakdown(q.getBasePrice(), q.getDistancePrice(), q.getWeightSurcharge(), q.getVolumeSurcharge(),
                q.getServiceSurcharge(), q.getAdditionalStopSurcharge(), q.getTimeSurcharge(), q.getOtherSurcharge());
        return new QuoteResponse(q.getId(), q.getQuoteCode(), q.getQuoteVersion(), q.getStatus(), q.getShipmentId(), s == null ? null : s.getShipmentCode(),
                q.getVehicleTypeCode(), q.getVehicleTypeCode() == null ? null : catalogs.vehicleType(q.getVehicleTypeCode()).map(VehicleTypeDto::name).orElse(q.getVehicleTypeCode()),
                q.getVehicleCount(), q.getDistanceKm(), q.getDistanceSource(), q.getRouteDurationMinutes(),
                s == null ? null : s.getTotalWeightKg(), s == null ? null : s.getTotalVolumeM3(), s == null ? 0 : s.getStops().size(),
                breakdown, q.getTotalPrice(), q.getCurrency(),
                q.getItems().stream().map(i -> new LineResponse(i.getLineNo(), i.getComponent(), i.getItemCode(), i.getItemName(), i.getDescription(),
                        i.getQuantity(), i.getUnitPrice(), i.getAmount())).toList(),
                q.getReviewReasonCode(), q.getReviewReason(), q.getRejectionReason(), q.getAdjustmentReason(),
                q.getExpiresAt(), q.getAcceptedAt(), q.getRejectedAt(), q.getSupersededAt(), q.getCreatedAt(),
                new QuoteActions(viewerIsOwner && live && shipmentOpen, viewerIsOwner && live,
                        viewerIsOwner && shipmentOpen && q.getStatus() != QuoteStatus.ACCEPTED && q.getStatus() != QuoteStatus.AWAITING_MANUAL_REVIEW));
    }

    public AdminQuoteResponse toAdminResponse(ShipmentQuote q, Shipment s) {
        return new AdminQuoteResponse(toResponse(q, s, false), s == null ? null : s.getCustomerId(),
                s == null ? null : s.getStops().stream().filter(x -> x.getStopType() == StopType.PICKUP).map(ShipmentStop::getFullAddress).findFirst().orElse(null),
                s == null ? null : s.getStops().stream().filter(x -> x.getStopType() == StopType.DELIVERY).map(ShipmentStop::getFullAddress).reduce((a, b) -> b).orElse(null),
                q.getAdjustedBy(), q.getPricingConfigId(), q.getPricingSnapshot());
    }
}
