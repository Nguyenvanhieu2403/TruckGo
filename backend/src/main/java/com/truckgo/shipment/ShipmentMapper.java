package com.truckgo.shipment;

import com.truckgo.catalog.CatalogService;
import com.truckgo.catalog.dto.CatalogDtos.ServiceDefinitionDto;
import com.truckgo.catalog.dto.CatalogDtos.ServiceOptionDto;
import com.truckgo.catalog.dto.CatalogDtos.VehicleTypeDto;
import com.truckgo.shipment.dto.ShipmentResponses.Actions;
import com.truckgo.shipment.dto.ShipmentResponses.AttachmentResponse;
import com.truckgo.shipment.dto.ShipmentResponses.ItemResponse;
import com.truckgo.shipment.dto.ShipmentResponses.PriceStatus;
import com.truckgo.shipment.dto.ShipmentResponses.ServiceResponse;
import com.truckgo.shipment.dto.ShipmentResponses.ShipmentDetail;
import com.truckgo.shipment.dto.ShipmentResponses.ShipmentSummary;
import com.truckgo.shipment.dto.ShipmentResponses.StatusHistoryResponse;
import com.truckgo.shipment.dto.ShipmentResponses.StopResponse;
import com.truckgo.shipment.dto.ShipmentResponses.StopServiceResponse;
import com.truckgo.shipment.dto.ShipmentResponses.VehicleRequirement;
import com.truckgo.shipment.dto.ShipmentResponses.Warning;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Component
public class ShipmentMapper {
    private static final BigDecimal CM3_PER_M3 = BigDecimal.valueOf(1_000_000);

    private final CatalogService catalogs;
    private final ShipmentPriceProvider prices;
    /** Resolved per call: the marketplace provider is optional and must not be needed to build this mapper. */
    private final ObjectProvider<ShipmentAssignmentProvider> assignments;

    @Autowired
    public ShipmentMapper(CatalogService catalogs, ObjectProvider<ShipmentPriceProvider> prices, ObjectProvider<ShipmentAssignmentProvider> assignments) {
        this.catalogs = catalogs;
        this.prices = prices.getIfAvailable(() -> id -> Optional.empty());
        this.assignments = assignments;
    }

    public ShipmentMapper(CatalogService catalogs) {
        this.catalogs = catalogs;
        this.prices = id -> Optional.empty();
        this.assignments = null;
    }

    public ShipmentDetail toDetail(Shipment s, List<ShipmentAttachment> attachments, List<ShipmentStatusHistory> history, boolean viewerIsOwner) {
        List<StopResponse> stops = s.getStops().stream().map(ShipmentMapper::stop).toList();
        Optional<ShipmentPriceProvider.CurrentPrice> price = prices.currentPrice(s.getId());
        return new ShipmentDetail(s.getId(), s.getShipmentCode(), s.getStatus(), s.getCustomerId(), s.getPickupTime(), s.getDeliveryDeadline(),
                vehicle(s), s.getTotalQuantity(), s.getTotalWeightKg(), s.getTotalVolumeM3(), s.getCustomerNote(), priceStatus(s, price),
                price.map(ShipmentPriceProvider.CurrentPrice::amount).orElse(s.getEstimatedPrice()), s.getFinalPrice(),
                stops.stream().filter(x -> x.stopType() == StopType.PICKUP).findFirst().orElse(null),
                stops.stream().filter(x -> x.stopType() == StopType.DELIVERY).reduce((a, b) -> b).orElse(null),
                stops, s.getItems().stream().map(this::item).toList(), s.getServices().stream().map(this::service).toList(),
                attachments.stream().map(a -> attachment(s, a)).toList(),
                history.stream().map(h -> new StatusHistoryResponse(h.getOldStatus(), h.getNewStatus(), h.getChangedBy(), h.getActorType().name(), h.getSystemActor(), h.getChangeReason(), h.getCreatedAt())).toList(),
                warnings(s), actions(s, viewerIsOwner), s.getPublishedAt(), s.getCancelledAt(), s.getCancellationReason(), s.getCreatedAt(), s.getUpdatedAt(), s.getVersion(),
                assignmentProvider().flatMap(p -> p.assignment(s)).orElse(null),
                s.getStatus() == ShipmentStatus.DRAFT ? null : assignmentProvider().map(p -> p.applicationStats(s.getId())).orElse(null),
                s.getStatus() == ShipmentStatus.DRAFT ? List.of() : assignmentProvider().map(p -> p.assignments(s)).orElse(List.of()));
    }

    private Optional<ShipmentAssignmentProvider> assignmentProvider() {
        return assignments == null ? Optional.empty() : Optional.ofNullable(assignments.getIfAvailable());
    }

    public ShipmentSummary toSummary(Shipment s, boolean viewerIsOwner) {
        List<ShipmentStop> deliveries = s.getStops().stream().filter(x -> x.getStopType() == StopType.DELIVERY).toList();
        String pickup = s.getStops().stream().filter(x -> x.getStopType() == StopType.PICKUP).map(ShipmentStop::getFullAddress).findFirst().orElse(null);
        String delivery = deliveries.isEmpty() ? null : deliveries.get(deliveries.size() - 1).getFullAddress();
        return new ShipmentSummary(s.getId(), s.getShipmentCode(), s.getStatus(), s.getCreatedAt(), s.getPickupTime(), pickup, delivery, deliveries.size(),
                s.getTotalQuantity(), s.getTotalWeightKg(), s.getTotalVolumeM3(), s.getVehicleTypeCode(),
                s.getVehicleTypeCode() == null ? null : catalogs.vehicleType(s.getVehicleTypeCode()).map(VehicleTypeDto::name).orElse(s.getVehicleTypeCode()),
                actions(s, viewerIsOwner));
    }

    public AttachmentResponse attachment(Shipment s, ShipmentAttachment a) {
        // A relative API path: the client never sees the storage endpoint, bucket or object key.
        return new AttachmentResponse(a.getId(), a.getFileName(), a.getContentType(), a.getFileSize(), a.getFileType(), a.getCreatedAt(),
                "/api/v1/shipments/" + s.getId() + "/attachments/" + a.getId() + "/content");
    }

    /**
     * Advisory checks against the nominal catalog values. They never claim a vehicle is suitable or legal; they only
     * flag obvious mismatches so the customer can correct the request.
     */
    public List<Warning> warnings(Shipment s) {
        List<Warning> warnings = new ArrayList<>();
        BigDecimal weight = s.getTotalWeightKg();
        if (s.getMinimumPayloadKg() != null && s.getMinimumPayloadKg().compareTo(weight) < 0)
            warnings.add(new Warning("MINIMUM_PAYLOAD_BELOW_WEIGHT", "Tải trọng tối thiểu " + plain(s.getMinimumPayloadKg()) + " kg thấp hơn tổng trọng lượng hàng " + plain(weight) + " kg"));
        Optional<VehicleTypeDto> type = s.getVehicleTypeCode() == null ? Optional.empty() : catalogs.vehicleType(s.getVehicleTypeCode());
        if (type.isPresent()) {
            VehicleTypeDto v = type.get();
            BigDecimal count = BigDecimal.valueOf(s.getVehicleCount());
            BigDecimal capacity = v.maxPayloadKg().multiply(count);
            if (capacity.compareTo(weight) < 0)
                warnings.add(new Warning("VEHICLE_PAYLOAD_INSUFFICIENT", "Tổng trọng lượng " + plain(weight) + " kg vượt tải trọng danh nghĩa " + plain(capacity) + " kg của " + s.getVehicleCount() + " × " + v.name()));
            if (s.getMinimumPayloadKg() != null && capacity.compareTo(s.getMinimumPayloadKg()) < 0)
                warnings.add(new Warning("VEHICLE_BELOW_MINIMUM_PAYLOAD", "Loại xe đã chọn có tải trọng danh nghĩa thấp hơn tải trọng tối thiểu yêu cầu"));
            if (v.cargoLengthCm() != null && v.cargoWidthCm() != null && v.cargoHeightCm() != null) {
                BigDecimal cargoVolume = v.cargoLengthCm().multiply(v.cargoWidthCm()).multiply(v.cargoHeightCm()).multiply(count).divide(CM3_PER_M3, 6, RoundingMode.HALF_UP);
                if (s.getTotalVolumeM3().compareTo(cargoVolume) > 0)
                    warnings.add(new Warning("VOLUME_EXCEEDS_CARGO", "Tổng thể tích " + plain(s.getTotalVolumeM3()) + " m³ có thể vượt thể tích thùng tham khảo " + plain(cargoVolume) + " m³"));
                BigDecimal longestCargo = Stream.of(v.cargoLengthCm(), v.cargoWidthCm(), v.cargoHeightCm()).max(Comparator.naturalOrder()).orElseThrow();
                s.getItems().stream().filter(ShipmentItem::hasDimensions)
                        .filter(i -> Stream.of(i.getLengthCm(), i.getWidthCm(), i.getHeightCm()).max(Comparator.naturalOrder()).orElseThrow().compareTo(longestCargo) > 0)
                        .findFirst()
                        .ifPresent(i -> warnings.add(new Warning("ITEM_EXCEEDS_CARGO_SIZE", "Kiện \"" + i.getItemName() + "\" có cạnh dài hơn thùng xe tham khảo của " + v.name())));
            }
        }
        if (s.getItems().stream().anyMatch(i -> !i.hasDimensions()))
            warnings.add(new Warning("DIMENSIONS_MISSING", "Một số hàng chưa có kích thước; cần bổ sung trước khi đăng đơn"));
        return warnings;
    }

    private static Actions actions(Shipment s, boolean owner) {
        ShipmentStatus status = s.getStatus();
        return new Actions(owner && status.isEditable(), owner && status == ShipmentStatus.DRAFT, owner && status.isCancellableByCustomer(),
                owner && status == ShipmentStatus.DRAFT, owner && status.isEditable());
    }

    private VehicleRequirement vehicle(Shipment s) {
        Optional<VehicleTypeDto> type = s.getVehicleTypeCode() == null ? Optional.empty() : catalogs.vehicleType(s.getVehicleTypeCode());
        String bodyName = s.getBodyTypeCode() == null ? null : catalogs.bodyType(s.getBodyTypeCode()).map(b -> b.name()).orElse(s.getBodyTypeCode());
        return new VehicleRequirement(s.getVehicleTypeCode(), type.map(VehicleTypeDto::name).orElse(s.getVehicleTypeCode()), type.map(VehicleTypeDto::maxPayloadKg).orElse(null),
                s.getBodyTypeCode(), bodyName, s.getVehicleCount(), s.getMinimumPayloadKg(), s.getMinCargoLengthCm(), s.getMinCargoWidthCm(), s.getMinCargoHeightCm());
    }

    private static PriceStatus priceStatus(Shipment s, Optional<ShipmentPriceProvider.CurrentPrice> price) {
        if (s.getFinalPrice() != null) return PriceStatus.FINAL;
        return price.map(p -> PriceStatus.valueOf(p.state().name())).orElse(PriceStatus.NOT_QUOTED);
    }

    private static StopResponse stop(ShipmentStop x) {
        return new StopResponse(x.getId(), x.getStopType(), x.getSequenceNo(), x.getContactName(), x.getContactPhone(), x.getProvince(), x.getDistrict(),
                x.getWard(), x.getAddressDetail(), x.getFullAddress(), x.getLatitude(), x.getLongitude(), x.getNote(),
                x.getServices().stream().map(v -> new StopServiceResponse(v.getServiceCode(), v.getServiceName(), v.getServiceValue())).toList());
    }

    private ItemResponse item(ShipmentItem i) {
        String name = catalogs.itemCategory(i.getCategoryCode()).map(c -> c.name()).orElse(i.getCategoryCode());
        return new ItemResponse(i.getId(), i.getItemName(), i.getCategoryCode(), name, i.getDescription(), i.getQuantity(), i.getWeightPerUnitKg(),
                i.getLengthCm(), i.getWidthCm(), i.getHeightCm(), i.getTotalWeightKg(), i.getTotalVolumeM3());
    }

    private ServiceResponse service(ShipmentExtraService x) {
        Optional<ServiceDefinitionDto> def = catalogs.shipmentService(x.getServiceCode());
        String label = def.flatMap(d -> d.options().stream().filter(o -> o.value().equals(x.getServiceValue())).map(ServiceOptionDto::label).findFirst()).orElse(null);
        return new ServiceResponse(x.getServiceCode(), x.getServiceName(), def.map(ServiceDefinitionDto::group).orElse(null),
                def.map(ServiceDefinitionDto::valueType).orElse(null), x.getServiceValue(), label);
    }

    private static String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
