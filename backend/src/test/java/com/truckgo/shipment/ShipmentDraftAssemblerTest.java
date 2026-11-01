package com.truckgo.shipment;

import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.shipment.dto.ShipmentRequests.DraftRequest;
import com.truckgo.shipment.dto.ShipmentRequests.ItemRequest;
import com.truckgo.shipment.dto.ShipmentRequests.ServiceRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.truckgo.shipment.ShipmentTestData.boxes;
import static com.truckgo.shipment.ShipmentTestData.delivery;
import static com.truckgo.shipment.ShipmentTestData.draft;
import static com.truckgo.shipment.ShipmentTestData.pickup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentDraftAssemblerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    private final ShipmentDraftAssembler assembler = new ShipmentDraftAssembler(ShipmentTestData.catalogs(), clock);

    private Shipment apply(DraftRequest request) {
        Shipment shipment = new Shipment("TG261101-AAAAAA", UUID.randomUUID());
        assembler.apply(shipment, request);
        return shipment;
    }

    private static void assertFieldError(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(FieldErrorException.class, ex -> assertThat(ex.errors()).containsKey(field));
    }

    @Test void buildsNormalisedOrder() {
        Shipment s = apply(ShipmentTestData.validDraft());
        assertThat(s.getStops()).extracting(ShipmentStop::getSequenceNo).containsExactly(1, 2);
        ShipmentStop pickup = s.getStops().get(0);
        assertThat(pickup.getContactPhone()).isEqualTo("+84912345678");
        assertThat(pickup.getFullAddress()).isEqualTo("Số 10, đường X, Mỹ Đình, Nam Từ Liêm, Hà Nội");
        assertThat(s.getStops().get(1).getLatitude()).as("no provider: coordinates are not invented").isNull();
        assertThat(s.getPickupTime()).isEqualTo(Instant.parse("2027-01-05T01:00:00Z"));
        assertThat(s.getTotalWeightKg()).isEqualByComparingTo("200");
        assertThat(s.getTotalVolumeM3()).isEqualByComparingTo("0.6");
        assertThat(s.getServices()).singleElement().satisfies(x -> {
            assertThat(x.getServiceName()).isEqualTo("Hàng dễ vỡ");
            assertThat(x.getServiceValue()).isEqualTo("true");
        });
    }

    @Test void requiresPickupDeliveryAndPickupFirst() {
        assertFieldError(() -> apply(draft(List.of(delivery(), delivery()), List.of(boxes()), null)), "stops");
        assertFieldError(() -> apply(draft(List.of(pickup(), pickup()), List.of(boxes()), null)), "stops");
        var reversed = List.of(new com.truckgo.shipment.dto.ShipmentRequests.StopRequest(StopType.DELIVERY, 1, "B", "0987654321", "Hà Nội", null, null, "X", null, null, null),
                new com.truckgo.shipment.dto.ShipmentRequests.StopRequest(StopType.PICKUP, 2, "A", "0912345678", "Hà Nội", null, null, "Y", null, null, null));
        assertFieldError(() -> apply(draft(reversed, List.of(boxes()), null)), "stops");
    }

    @Test void supportsMultipleDeliveriesOrderedBySequence() {
        var second = new com.truckgo.shipment.dto.ShipmentRequests.StopRequest(StopType.DELIVERY, 3, "C", "0901234567", "Hà Nội", null, null, "Số 30", null, null, null);
        Shipment s = apply(draft(List.of(second, delivery(), pickup()), List.of(boxes()), null));
        assertThat(s.getStops()).extracting(ShipmentStop::getContactName).containsExactly("Nguyen Van A", "Nguyen Van B", "C");
    }

    @Test void validatesPhonesCoordinatesAndCategories() {
        var badPhone = new com.truckgo.shipment.dto.ShipmentRequests.StopRequest(StopType.PICKUP, 1, "A", "123", "Hà Nội", null, null, "X", new BigDecimal("21"), null, null);
        assertThatThrownBy(() -> apply(draft(List.of(badPhone, delivery()), List.of(new ItemRequest("X", "WEAPONS", null, 1, BigDecimal.ONE, null, null, null)), null)))
                .isInstanceOfSatisfying(FieldErrorException.class, ex -> assertThat(ex.errors()).containsKeys("stops[0].contactPhone", "stops[0].latitude", "items[0].category"));
    }

    @Test void partialDimensionsAreRejected() {
        assertFieldError(() -> apply(draft(List.of(pickup(), delivery()), List.of(new ItemRequest("X", "OTHER", null, 1, BigDecimal.ONE, BigDecimal.TEN, null, null)), null)),
                "items[0].lengthCm");
    }

    @Test void validatesServicesAgainstCatalog() {
        assertFieldError(() -> apply(draft(List.of(pickup(), delivery()), List.of(boxes()), List.of(new ServiceRequest("FREE_PIZZA", "true")))), "services[0].serviceCode");
        assertFieldError(() -> apply(draft(List.of(pickup(), delivery()), List.of(boxes()),
                List.of(new ServiceRequest("ELEVATOR_AVAILABLE", "true"), new ServiceRequest("ELEVATOR_UNAVAILABLE", "true")))), "services[1].serviceCode");
        assertFieldError(() -> apply(draft(List.of(pickup(), delivery()), List.of(boxes()), List.of(new ServiceRequest("UPSTAIRS_DELIVERY", "500")))), "services[0].serviceValue");
        assertFieldError(() -> apply(draft(List.of(pickup(), delivery()), List.of(boxes()), List.of(new ServiceRequest("LOADING_SUPPORT", "ROBOTS")))), "services[0].serviceValue");
        assertFieldError(() -> apply(draft(List.of(pickup(), delivery()), List.of(boxes()), List.of(new ServiceRequest("SPECIAL_REQUIREMENT", " ")))), "services[0].serviceValue");
        Shipment s = apply(draft(List.of(pickup(), delivery()), List.of(boxes()), List.of(new ServiceRequest("FRAGILE_GOODS", "false"), new ServiceRequest("LOADING_SUPPORT", "TEAM"))));
        assertThat(s.getServices()).extracting(ShipmentExtraService::getServiceCode).containsExactly("LOADING_SUPPORT");
    }

    @Test void rejectsInvertedTimeWindowAndUnknownVehicle() {
        var request = new DraftRequest(OffsetDateTime.parse("2027-01-05T18:00:00+07:00"), OffsetDateTime.parse("2027-01-05T08:00:00+07:00"), "SPACESHIP", null, 1,
                null, null, null, null, null, List.of(pickup(), delivery()), List.of(boxes()), null, null);
        assertThatThrownBy(() -> apply(request)).isInstanceOfSatisfying(FieldErrorException.class,
                ex -> assertThat(ex.errors()).containsKeys("deliveryDeadline", "vehicleTypeCode"));
    }

    @Test void publishRequiresFuturePickupAndDimensions() {
        var noTime = new DraftRequest(null, null, null, null, 1, null, null, null, null, null, List.of(pickup(), delivery()),
                List.of(new ItemRequest("Bao gạo", "OTHER", null, 2, BigDecimal.TEN, null, null, null)), null, null);
        Shipment s = apply(noTime);
        assertThatThrownBy(() -> assembler.validateForPublish(s, Duration.ofMinutes(15)))
                .isInstanceOfSatisfying(FieldErrorException.class, ex -> assertThat(ex.errors()).containsKeys("pickupTime", "items[0].lengthCm"));
        Shipment ok = apply(ShipmentTestData.validDraft());
        assertThatCode(() -> assembler.validateForPublish(ok, Duration.ofMinutes(15))).doesNotThrowAnyException();
        ok.setSchedule(Instant.parse("2026-11-01T00:10:00Z"), null);
        assertThatThrownBy(() -> assembler.validateForPublish(ok, Duration.ofMinutes(15))).isInstanceOf(FieldErrorException.class);
    }
}
