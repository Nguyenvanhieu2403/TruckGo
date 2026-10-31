package com.truckgo.marketplace;

import com.truckgo.catalog.CatalogService;
import com.truckgo.catalog.dto.CatalogDtos.VehicleTypeDto;
import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentExtraService;
import com.truckgo.shipment.ShipmentItem;
import com.truckgo.vehicle.Vehicle;
import com.truckgo.vehicle.VehicleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VehicleEligibilityTest {
    private static final LocalDate PICKUP = LocalDate.of(2026, 11, 10);
    private final CatalogService catalogs = mock(CatalogService.class);
    private final VehicleEligibility eligibility = new VehicleEligibility(catalogs);

    VehicleEligibilityTest() {
        when(catalogs.vehicleTypes()).thenReturn(List.of(type("TRUCK_500KG", 500), type("TRUCK_1T", 1000), type("TRUCK_1_5T", 1500)));
    }

    private static VehicleTypeDto type(String code, int payload) { return new VehicleTypeDto(code, code, BigDecimal.valueOf(payload), null, null, null); }

    /** 1-tonne order: 10 boxes of 60 kg, 60x60x90 cm. */
    private static Shipment order(String vehicleType, String bodyType) {
        Shipment s = new Shipment("TG261110-ABCDEF", UUID.randomUUID());
        s.setVehicleRequirement(vehicleType, bodyType, 1, null, null, null, null);
        s.addItem(new ShipmentItem("Máy giặt", "ELECTRONICS", null, 10, new BigDecimal("60"), new BigDecimal("60"), new BigDecimal("60"), new BigDecimal("90"), Instant.now()));
        s.recalculateTotals();
        return s;
    }

    private static Vehicle vehicle(VehicleType type, int payload, int length, int width, int height, LocalDate expiry, VerificationStatus status) {
        Vehicle v = new Vehicle(UUID.randomUUID(), "51C12345", type, payload, length, width, height, expiry);
        v.review(status, UUID.randomUUID(), null);
        return v;
    }

    private static Vehicle good() { return vehicle(VehicleType.SMALL_TRUCK, 1200, 420, 180, 190, PICKUP.plusYears(1), VerificationStatus.APPROVED); }

    @Test void approvedVehicleOfTheRightClassIsEligible() {
        assertThat(eligibility.reasons(order("TRUCK_1T", "CLOSED_BOX"), good(), PICKUP)).isEmpty();
    }

    @Test void vehicleMustBeApprovedAndRegisteredOnPickupDay() {
        Vehicle pending = vehicle(VehicleType.SMALL_TRUCK, 1200, 420, 180, 190, PICKUP.plusYears(1), VerificationStatus.PENDING_REVIEW);
        assertThat(eligibility.reasons(order("TRUCK_1T", null), pending, PICKUP)).containsExactly("Xe chưa được duyệt");
        Vehicle expired = vehicle(VehicleType.SMALL_TRUCK, 1200, 420, 180, 190, PICKUP.minusDays(1), VerificationStatus.APPROVED);
        assertThat(eligibility.reasons(order("TRUCK_1T", null), expired, PICKUP)).containsExactly("Đăng kiểm hết hạn trước ngày lấy hàng");
        Vehicle lastDay = vehicle(VehicleType.SMALL_TRUCK, 1200, 420, 180, 190, PICKUP, VerificationStatus.APPROVED);
        assertThat(eligibility.reasons(order("TRUCK_1T", null), lastDay, PICKUP)).isEmpty();
    }

    @Test void payloadMustCoverTheCargoAndTheRequestedClass() {
        // 500 kg is the class below "1 tấn" and below the 600 kg cargo.
        Vehicle tiny = vehicle(VehicleType.VAN, 500, 420, 180, 190, PICKUP.plusYears(1), VerificationStatus.APPROVED);
        assertThat(eligibility.reasons(order("TRUCK_1T", null), tiny, PICKUP))
                .contains("Tải trọng xe nhỏ hơn tổng khối lượng hàng", "Xe thuộc hạng nhỏ hơn loại xe khách chọn");
        // 700 kg is above the 500 kg class, so it counts as a 1-tonne truck, but it is not a 1,5-tonne one.
        Vehicle small = vehicle(VehicleType.VAN, 700, 420, 180, 190, PICKUP.plusYears(1), VerificationStatus.APPROVED);
        assertThat(eligibility.reasons(order("TRUCK_1T", null), small, PICKUP)).isEmpty();
        assertThat(eligibility.reasons(order("TRUCK_1_5T", null), small, PICKUP)).containsExactly("Xe thuộc hạng nhỏ hơn loại xe khách chọn");
        // The smallest class has no lower bound; a bigger truck is always fine.
        assertThat(eligibility.reasons(order("TRUCK_500KG", null), small, PICKUP)).isEmpty();
        assertThat(eligibility.reasons(order("TRUCK_500KG", null), good(), PICKUP)).isEmpty();
    }

    @Test void itemsMustFitTheBoxInAnyOrientation() {
        // 60x60x90 fits a 100x70x65 box when turned on its side.
        Vehicle turned = vehicle(VehicleType.SMALL_TRUCK, 1200, 100, 70, 65, PICKUP.plusYears(1), VerificationStatus.APPROVED);
        assertThat(eligibility.reasons(order("TRUCK_1T", null), turned, PICKUP)).doesNotContain("Có kiện hàng không vừa thùng xe");
        Vehicle low = vehicle(VehicleType.SMALL_TRUCK, 1200, 400, 180, 55, PICKUP.plusYears(1), VerificationStatus.APPROVED);
        assertThat(eligibility.reasons(order("TRUCK_1T", null), low, PICKUP)).contains("Có kiện hàng không vừa thùng xe");
        // 10 x 0.324 m³ = 3.24 m³ does not go into 100x70x65 cm (0.455 m³).
        assertThat(eligibility.reasons(order("TRUCK_1T", null), turned, PICKUP)).contains("Tổng thể tích hàng lớn hơn thể tích thùng xe");
    }

    @Test void coldChainAndContainerNeedTheMatchingVehicle() {
        assertThat(eligibility.reasons(order("TRUCK_1T", "REFRIGERATED"), good(), PICKUP)).containsExactly("Đơn cần xe thùng đông lạnh");
        Shipment cold = order("TRUCK_1T", null);
        cold.addService(new ShipmentExtraService(VehicleEligibility.COLD_CHAIN_SERVICE, "Cần giữ lạnh", "true", null));
        assertThat(eligibility.reasons(cold, good(), PICKUP)).containsExactly("Đơn cần xe thùng đông lạnh");
        Vehicle reefer = vehicle(VehicleType.REFRIGERATED_TRUCK, 1200, 420, 180, 190, PICKUP.plusYears(1), VerificationStatus.APPROVED);
        assertThat(eligibility.reasons(cold, reefer, PICKUP)).isEmpty();
        assertThat(eligibility.reasons(order("TRUCK_1T", "CONTAINER"), good(), PICKUP)).containsExactly("Đơn cần xe container");
    }
}
