package com.truckgo.vehicle;

import com.truckgo.catalog.CatalogService;
import com.truckgo.catalog.dto.CatalogDtos.BodyTypeDto;
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
    private final CatalogService catalogs;
    private final Clock clock;

    public VehicleService(VehicleRepository vehicles, DriverService drivers, CatalogService catalogs, Clock clock) {
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.catalogs = catalogs;
        this.clock = clock;
    }

    @Transactional
    public VehicleResponse create(UUID userId, CreateVehicleRequest request) {
        DriverProfile profile = drivers.requireProfile(userId);
        String plate = PlateNumberNormalizer.normalize(request.plateNumber());
        requireFutureExpiry(request.registrationExpiryDate());
        if (vehicles.existsByPlateNumber(plate)) throw FieldErrorException.conflict("plateNumber", "Biển số xe đã được đăng ký");
        String bodyType = requireBodyType(request.bodyTypeCode(), request.vehicleType());
        Vehicle vehicle = new Vehicle(profile.getId(), plate, request.vehicleType(), request.maxPayloadKg(), request.lengthCm(),
                request.widthCm(), request.heightCm(), request.registrationExpiryDate());
        vehicle.setBodyTypeCode(bodyType);
        return response(vehicles.save(vehicle));
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> listMine(UUID userId) {
        DriverProfile profile = drivers.requireProfile(userId);
        return vehicles.findByDriverIdOrderByCreatedAtDesc(profile.getId()).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public VehicleResponse getMine(UUID userId, UUID vehicleId) { return response(requireOwned(userId, vehicleId)); }

    @Transactional
    public VehicleResponse update(UUID userId, UUID vehicleId, UpdateVehicleRequest request) {
        Vehicle vehicle = requireOwned(userId, vehicleId);
        if (vehicle.getVerificationStatus() == VerificationStatus.PENDING_REVIEW)
            throw new ConflictException("Phương tiện đang chờ duyệt, không thể chỉnh sửa lúc này");
        String plate = request.plateNumber() == null ? null : PlateNumberNormalizer.normalize(request.plateNumber());
        if (plate != null && vehicles.existsByPlateNumberAndIdNot(plate, vehicle.getId()))
            throw FieldErrorException.conflict("plateNumber", "Biển số xe đã được đăng ký");
        if (request.registrationExpiryDate() != null) requireFutureExpiry(request.registrationExpiryDate());
        VehicleType type = request.vehicleType() != null ? request.vehicleType() : vehicle.getVehicleType();
        String bodyType = request.bodyTypeCode() != null ? requireBodyType(request.bodyTypeCode(), type)
                : request.vehicleType() != null && vehicle.getBodyTypeCode() != null ? requireBodyType(vehicle.getBodyTypeCode(), type) : null;
        vehicle.update(plate, request.vehicleType(), bodyType, request.maxPayloadKg(), request.lengthCm(), request.widthCm(), request.heightCm(), request.registrationExpiryDate());
        return response(vehicle);
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
        if (vehicle.getBodyTypeCode() == null) throw FieldErrorException.invalid("bodyTypeCode", "Vui lòng khai báo loại thùng xe trước khi gửi duyệt");
        vehicle.markSubmitted();
        return response(vehicle);
    }

    private VehicleResponse response(Vehicle v) {
        return VehicleResponse.from(v, v.getBodyTypeCode() == null ? null : catalogs.bodyType(v.getBodyTypeCode()).map(BodyTypeDto::name).orElse(v.getBodyTypeCode()));
    }

    /**
     * The body type must be an active entry of the vehicle_body_types catalog other than "ANY" (that one only means
     * "no requirement" on an order), and must not contradict the vehicle type (a refrigerated truck has a refrigerated
     * body, a container truck carries a container).
     */
    private String requireBodyType(String code, VehicleType type) {
        String value = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (BodyTypes.ANY.equals(value) || catalogs.bodyType(value).isEmpty())
            throw FieldErrorException.invalid("bodyTypeCode", "Loại thùng xe không hợp lệ");
        String required = BodyTypes.requiredFor(type);
        if (required != null && !required.equals(value))
            throw FieldErrorException.invalid("bodyTypeCode", type == VehicleType.REFRIGERATED_TRUCK ? "Xe đông lạnh phải có thùng đông lạnh" : "Xe container phải chọn loại thùng Container");
        return value;
    }

    private Vehicle requireOwned(UUID userId, UUID vehicleId) {
        DriverProfile profile = drivers.requireProfile(userId);
        return vehicles.findByIdAndDriverId(vehicleId, profile.getId()).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phương tiện"));
    }

    private void requireFutureExpiry(LocalDate expiry) {
        if (!expiry.isAfter(LocalDate.now(clock))) throw FieldErrorException.invalid("registrationExpiryDate", "Đăng kiểm/đăng ký xe phải còn hạn");
    }
}
