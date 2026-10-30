package com.truckgo.vehicle;

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
    private final VehicleService service = new VehicleService(vehicles, drivers, clock);
    private final UUID userId = UUID.randomUUID();
    private final DriverProfile profile = new DriverProfile(userId, "012345678901", "C");

    @BeforeEach void setUp() {
        when(drivers.requireProfile(userId)).thenReturn(profile);
        when(vehicles.save(any(Vehicle.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test void ownerComesFromAuthenticatedDriver() {
        var created = service.create(userId, new CreateVehicleRequest("51C-123.45", VehicleType.SMALL_TRUCK, 1500, 400, 180, 180, LocalDate.of(2027, 1, 1)));
        assertThat(created.driverId()).isEqualTo(profile.getId());
        assertThat(created.plateNumber()).isEqualTo("51C12345");
        assertThat(created.verificationStatus()).isEqualTo(VerificationStatus.NOT_SUBMITTED);
    }

    @Test void expiredRegistrationIsRejected() {
        assertThatThrownBy(() -> service.create(userId, new CreateVehicleRequest("51C-123.45", VehicleType.SMALL_TRUCK, 1500, 400, 180, 180, LocalDate.of(2026, 10, 30))))
                .isInstanceOfSatisfying(FieldErrorException.class, ex -> assertThat(ex.errors()).containsKey("registrationExpiryDate"));
    }

    @Test void vehicleOfAnotherDriverIsNotFound() {
        UUID foreignVehicle = UUID.randomUUID();
        when(vehicles.findByIdAndDriverId(foreignVehicle, profile.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMine(userId, foreignVehicle)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(userId, foreignVehicle, new UpdateVehicleRequest(null, null, 2000, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void changingApprovedVehicleSendsItBackToReview() {
        Vehicle vehicle = new Vehicle(profile.getId(), "51C12345", VehicleType.SMALL_TRUCK, 1500, 400, 180, 180, LocalDate.of(2027, 1, 1));
        vehicle.markSubmitted();
        vehicle.review(VerificationStatus.APPROVED, UUID.randomUUID(), null);
        when(vehicles.findByIdAndDriverId(vehicle.getId(), profile.getId())).thenReturn(Optional.of(vehicle));
        var updated = service.update(userId, vehicle.getId(), new UpdateVehicleRequest(null, null, 2500, null, null, null, null));
        assertThat(updated.verificationStatus()).isEqualTo(VerificationStatus.PENDING_REVIEW);
        assertThat(updated.maxPayloadKg()).isEqualTo(2500);
    }
}
