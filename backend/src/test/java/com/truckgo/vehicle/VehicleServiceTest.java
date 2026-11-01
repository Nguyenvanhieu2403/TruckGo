package com.truckgo.vehicle;

import com.truckgo.catalog.CatalogService;
import com.truckgo.catalog.dto.CatalogDtos.BodyTypeDto;
import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverService;
import com.truckgo.vehicle.dto.CreateVehicleRequest;
import com.truckgo.vehicle.dto.UpdateVehicleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VehicleServiceTest {
    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final DriverService drivers = mock(DriverService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC);
    private final CatalogService catalogs = mock(CatalogService.class);
    private final VehicleService service = new VehicleService(vehicles, drivers, catalogs, clock);
    private final UUID userId = UUID.randomUUID();
    private final DriverProfile profile = new DriverProfile(userId, "012345678901", "C");

    @BeforeEach void setUp() {
        when(drivers.requireProfile(userId)).thenReturn(profile);
        when(vehicles.save(any(Vehicle.class))).thenAnswer(inv -> inv.getArgument(0));
        for (String code : List.of("ANY", "OPEN", "CLOSED_BOX", "REFRIGERATED", "CONTAINER"))
            when(catalogs.bodyType(code)).thenReturn(Optional.of(new BodyTypeDto(code, code)));
    }

    private static CreateVehicleRequest request(VehicleType type, String body, LocalDate expiry) {
        return new CreateVehicleRequest("51C-123.45", type, body, 1500, 400, 180, 180, expiry);
    }

    @Test void ownerComesFromAuthenticatedDriver() {
        var created = service.create(userId, request(VehicleType.SMALL_TRUCK, "CLOSED_BOX", LocalDate.of(2027, 1, 1)));
        assertThat(created.driverId()).isEqualTo(profile.getId());
        assertThat(created.plateNumber()).isEqualTo("51C12345");
        assertThat(created.verificationStatus()).isEqualTo(VerificationStatus.NOT_SUBMITTED);
        assertThat(created.bodyTypeCode()).isEqualTo("CLOSED_BOX");
    }

    @Test void bodyTypeMustBeARealCatalogEntryMatchingTheVehicleType() {
        for (String bad : List.of("ANY", "SPACESHIP"))
            assertThatThrownBy(() -> service.create(userId, request(VehicleType.SMALL_TRUCK, bad, LocalDate.of(2027, 1, 1))))
                    .isInstanceOfSatisfying(FieldErrorException.class, ex -> assertThat(ex.errors()).containsKey("bodyTypeCode"));
        assertThatThrownBy(() -> service.create(userId, request(VehicleType.REFRIGERATED_TRUCK, "OPEN", LocalDate.of(2027, 1, 1))))
                .isInstanceOfSatisfying(FieldErrorException.class, ex -> assertThat(ex.errors()).containsEntry("bodyTypeCode", "Xe đông lạnh phải có thùng đông lạnh"));
        assertThatThrownBy(() -> service.create(userId, request(VehicleType.CONTAINER_TRUCK, "CLOSED_BOX", LocalDate.of(2027, 1, 1))))
                .isInstanceOf(FieldErrorException.class);
        assertThat(service.create(userId, request(VehicleType.SMALL_TRUCK, "refrigerated", LocalDate.of(2027, 1, 1))).bodyTypeCode()).isEqualTo("REFRIGERATED");
    }

    @Test void legacyVehicleWithoutBodyTypeMustDeclareItBeforeReviewAndDeclaringItIsReviewed() {
        Vehicle legacy = new Vehicle(profile.getId(), "51C12345", VehicleType.SMALL_TRUCK, 1500, 400, 180, 180, LocalDate.of(2027, 1, 1));
        legacy.markSubmitted();
        legacy.review(VerificationStatus.APPROVED, UUID.randomUUID(), null);
        when(vehicles.findByIdAndDriverId(legacy.getId(), profile.getId())).thenReturn(Optional.of(legacy));
        assertThat(service.getMine(userId, legacy.getId()).bodyTypeCode()).isNull();   // no invented body type
        var declared = service.update(userId, legacy.getId(), new UpdateVehicleRequest(null, null, "OPEN", null, null, null, null, null));
        assertThat(declared.bodyTypeCode()).isEqualTo("OPEN");
        assertThat(declared.verificationStatus()).isEqualTo(VerificationStatus.PENDING_REVIEW);

        Vehicle fresh = new Vehicle(profile.getId(), "51C99999", VehicleType.SMALL_TRUCK, 1500, 400, 180, 180, LocalDate.of(2027, 1, 1));
        when(vehicles.findByIdAndDriverId(fresh.getId(), profile.getId())).thenReturn(Optional.of(fresh));
        assertThatThrownBy(() -> service.submitForVerification(userId, fresh.getId()))
                .isInstanceOfSatisfying(FieldErrorException.class, ex -> assertThat(ex.errors()).containsKey("bodyTypeCode"));
    }

    @Test void expiredRegistrationIsRejected() {
        assertThatThrownBy(() -> service.create(userId, request(VehicleType.SMALL_TRUCK, "CLOSED_BOX", LocalDate.of(2026, 10, 30))))
                .isInstanceOfSatisfying(FieldErrorException.class, ex -> assertThat(ex.errors()).containsKey("registrationExpiryDate"));
    }

    @Test void vehicleOfAnotherDriverIsNotFound() {
        UUID foreignVehicle = UUID.randomUUID();
        when(vehicles.findByIdAndDriverId(foreignVehicle, profile.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMine(userId, foreignVehicle)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(userId, foreignVehicle, new UpdateVehicleRequest(null, null, null, 2000, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void changingApprovedVehicleSendsItBackToReview() {
        Vehicle vehicle = new Vehicle(profile.getId(), "51C12345", VehicleType.SMALL_TRUCK, 1500, 400, 180, 180, LocalDate.of(2027, 1, 1));
        vehicle.markSubmitted();
        vehicle.review(VerificationStatus.APPROVED, UUID.randomUUID(), null);
        when(vehicles.findByIdAndDriverId(vehicle.getId(), profile.getId())).thenReturn(Optional.of(vehicle));
        var updated = service.update(userId, vehicle.getId(), new UpdateVehicleRequest(null, null, null, 2500, null, null, null, null));
        assertThat(updated.verificationStatus()).isEqualTo(VerificationStatus.PENDING_REVIEW);
        assertThat(updated.maxPayloadKg()).isEqualTo(2500);
    }
}
