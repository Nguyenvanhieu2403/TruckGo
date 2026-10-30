package com.truckgo.vehicle;

import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.common.exception.ConflictException;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverService;
import com.truckgo.vehicle.dto.CreateVehicleRequest;
import com.truckgo.vehicle.dto.UpdateVehicleRequest;
import com.truckgo.vehicle.dto.VehicleResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Every lookup is scoped to the caller's own driver profile, so a vehicle owned by someone else is reported as
 * "not found" rather than revealing that the id exists.
 */
@Service
public class VehicleService {
    private final VehicleRepository vehicles;
    private final DriverService drivers;
    private final Clock clock;

    public VehicleService(VehicleRepository vehicles, DriverService drivers, Clock clock) {
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.clock = clock;
    }

    @Transactional
    public VehicleResponse create(UUID userId, CreateVehicleRequest request) {
        DriverProfile profile = drivers.requireProfile(userId);
        String plate = PlateNumberNormalizer.normalize(request.plateNumber());
        requireFutureExpiry(request.registrationExpiryDate());
        if (vehicles.existsByPlateNumber(plate)) throw FieldErrorException.conflict("plateNumber", "Biển số xe đã được đăng ký");
        Vehicle vehicle = new Vehicle(profile.getId(), plate, request.vehicleType(), request.maxPayloadKg(), request.lengthCm(),
                request.widthCm(), request.heightCm(), request.registrationExpiryDate());
        return VehicleResponse.from(vehicles.save(vehicle));
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> listMine(UUID userId) {
        DriverProfile profile = drivers.requireProfile(userId);
        return vehicles.findByDriverIdOrderByCreatedAtDesc(profile.getId()).stream().map(VehicleResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public VehicleResponse getMine(UUID userId, UUID vehicleId) { return VehicleResponse.from(requireOwned(userId, vehicleId)); }

    @Transactional
    public VehicleResponse update(UUID userId, UUID vehicleId, UpdateVehicleRequest request) {
        Vehicle vehicle = requireOwned(userId, vehicleId);
        if (vehicle.getVerificationStatus() == VerificationStatus.PENDING_REVIEW)
            throw new ConflictException("Phương tiện đang chờ duyệt, không thể chỉnh sửa lúc này");
        String plate = request.plateNumber() == null ? null : PlateNumberNormalizer.normalize(request.plateNumber());
        if (plate != null && vehicles.existsByPlateNumberAndIdNot(plate, vehicle.getId()))
            throw FieldErrorException.conflict("plateNumber", "Biển số xe đã được đăng ký");
        if (request.registrationExpiryDate() != null) requireFutureExpiry(request.registrationExpiryDate());
        vehicle.update(plate, request.vehicleType(), request.maxPayloadKg(), request.lengthCm(), request.widthCm(), request.heightCm(), request.registrationExpiryDate());
        return VehicleResponse.from(vehicle);
    }

    @Transactional
    public VehicleResponse submitForVerification(UUID userId, UUID vehicleId) {
        Vehicle vehicle = requireOwned(userId, vehicleId);
        switch (vehicle.getVerificationStatus()) {
            case PENDING_REVIEW -> throw new ConflictException("Phương tiện đã được gửi và đang chờ duyệt");
            case APPROVED -> throw new ConflictException("Phương tiện đã được duyệt");
            default -> { }
        }
        requireFutureExpiry(vehicle.getRegistrationExpiryDate());
        vehicle.markSubmitted();
        return VehicleResponse.from(vehicle);
    }

    private Vehicle requireOwned(UUID userId, UUID vehicleId) {
        DriverProfile profile = drivers.requireProfile(userId);
        return vehicles.findByIdAndDriverId(vehicleId, profile.getId()).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phương tiện"));
    }

    private void requireFutureExpiry(LocalDate expiry) {
        if (!expiry.isAfter(LocalDate.now(clock))) throw FieldErrorException.invalid("registrationExpiryDate", "Đăng kiểm/đăng ký xe phải còn hạn");
    }
}
