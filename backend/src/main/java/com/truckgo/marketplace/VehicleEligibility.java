package com.truckgo.marketplace;

import com.truckgo.catalog.CatalogService;
import com.truckgo.catalog.dto.CatalogDtos.VehicleTypeDto;
import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentItem;
import com.truckgo.vehicle.Vehicle;
import com.truckgo.vehicle.VehicleType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Whether one of a driver's vehicles can carry an order. Orders ask for a payload class from the catalog, vehicles
 * have a real payload and box size, so the check compares capacities rather than type codes. Returns the reasons a
 * vehicle does not fit (empty = eligible) so they can be shown to the driver.
 */
@Component
public class VehicleEligibility {
    static final String COLD_CHAIN_SERVICE = "COLD_CHAIN";
    private static final BigDecimal CM3_PER_M3 = BigDecimal.valueOf(1_000_000);

    private final CatalogService catalogs;

    public VehicleEligibility(CatalogService catalogs) { this.catalogs = catalogs; }

    public List<String> reasons(Shipment shipment, Vehicle vehicle, LocalDate pickupDate) {
        List<String> reasons = new ArrayList<>();
        if (vehicle.getVerificationStatus() != VerificationStatus.APPROVED) reasons.add("Xe chưa được duyệt");
        if (pickupDate != null && vehicle.getRegistrationExpiryDate().isBefore(pickupDate)) reasons.add("Đăng kiểm hết hạn trước ngày lấy hàng");

        BigDecimal payload = BigDecimal.valueOf(vehicle.getMaxPayloadKg());
        if (payload.compareTo(shipment.getTotalWeightKg()) < 0) reasons.add("Tải trọng xe nhỏ hơn tổng khối lượng hàng");
        if (shipment.getMinimumPayloadKg() != null && payload.compareTo(shipment.getMinimumPayloadKg()) < 0) reasons.add("Tải trọng xe nhỏ hơn tải trọng tối thiểu khách yêu cầu");
        classFloor(shipment.getVehicleTypeCode()).filter(floor -> payload.compareTo(floor) <= 0)
                .ifPresent(floor -> reasons.add("Xe thuộc hạng nhỏ hơn loại xe khách chọn"));

        BigDecimal[] box = {cm(vehicle.getLengthCm()), cm(vehicle.getWidthCm()), cm(vehicle.getHeightCm())};
        if (below(box[0], shipment.getMinCargoLengthCm()) || below(box[1], shipment.getMinCargoWidthCm()) || below(box[2], shipment.getMinCargoHeightCm()))
            reasons.add("Kích thước thùng xe nhỏ hơn yêu cầu");
        if (shipment.getItems().stream().anyMatch(item -> !fits(item, box))) reasons.add("Có kiện hàng không vừa thùng xe");
        BigDecimal boxVolume = box[0].multiply(box[1]).multiply(box[2]).divide(CM3_PER_M3);
        if (shipment.getTotalVolumeM3().compareTo(boxVolume) > 0) reasons.add("Tổng thể tích hàng lớn hơn thể tích thùng xe");

        boolean needsCold = "REFRIGERATED".equals(shipment.getBodyTypeCode())
                || shipment.getServices().stream().anyMatch(s -> COLD_CHAIN_SERVICE.equals(s.getServiceCode()) && "true".equalsIgnoreCase(s.getServiceValue()));
        if (needsCold && vehicle.getVehicleType() != VehicleType.REFRIGERATED_TRUCK) reasons.add("Đơn cần xe thùng đông lạnh");
        if ("CONTAINER".equals(shipment.getBodyTypeCode()) && vehicle.getVehicleType() != VehicleType.CONTAINER_TRUCK) reasons.add("Đơn cần xe container");
        return reasons;
    }

    /**
     * A vehicle belongs to the requested class when its payload is above the next smaller class, e.g. a 1,2 t truck
     * qualifies for "1,5 tấn" but a 1 t truck does not. Empty when no class was requested or it is the smallest one.
     */
    private Optional<BigDecimal> classFloor(String vehicleTypeCode) {
        if (vehicleTypeCode == null) return Optional.empty();
        List<VehicleTypeDto> types = catalogs.vehicleTypes();
        for (int i = 0; i < types.size(); i++)
            if (types.get(i).code().equals(vehicleTypeCode)) return i == 0 ? Optional.empty() : Optional.ofNullable(types.get(i - 1).maxPayloadKg());
        return Optional.empty();
    }

    /** Compares the item's sides with the box's sides, both sorted, so an item may be turned to fit. */
    private static boolean fits(ShipmentItem item, BigDecimal[] box) {
        if (item.getLengthCm() == null || item.getWidthCm() == null || item.getHeightCm() == null) return true;
        BigDecimal[] sides = {item.getLengthCm(), item.getWidthCm(), item.getHeightCm()};
        BigDecimal[] room = box.clone();
        Arrays.sort(sides);
        Arrays.sort(room);
        for (int i = 0; i < 3; i++) if (sides[i].compareTo(room[i]) > 0) return false;
        return true;
    }

    private static boolean below(BigDecimal actual, BigDecimal required) { return required != null && actual.compareTo(required) < 0; }

    private static BigDecimal cm(Integer value) { return BigDecimal.valueOf(value); }
}
