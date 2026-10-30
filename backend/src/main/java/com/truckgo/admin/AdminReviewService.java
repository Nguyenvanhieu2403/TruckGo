package com.truckgo.admin;

import com.truckgo.admin.dto.AdminDriverResponse;
import com.truckgo.admin.dto.AdminVehicleResponse;
import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.common.exception.ConflictException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverProfileRepository;
import com.truckgo.driver.dto.DriverProfileResponse;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import com.truckgo.vehicle.Vehicle;
import com.truckgo.vehicle.VehicleRepository;
import com.truckgo.vehicle.dto.VehicleResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Approve/reject flows. Only items in PENDING_REVIEW can be decided; each decision is audited in the same transaction. */
@Service
public class AdminReviewService {
    private final DriverProfileRepository profiles;
    private final VehicleRepository vehicles;
    private final UserRepository users;
    private final AdminAuditService audit;

    public AdminReviewService(DriverProfileRepository profiles, VehicleRepository vehicles, UserRepository users, AdminAuditService audit) {
        this.profiles = profiles;
        this.vehicles = vehicles;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminDriverResponse> pendingDrivers(Pageable pageable) {
        Page<DriverProfile> page = profiles.findByVerificationStatus(VerificationStatus.PENDING_REVIEW, pageable);
        Map<UUID, User> owners = usersById(page.getContent().stream().map(DriverProfile::getUserId).collect(Collectors.toSet()));
        return PageResponse.of(page, p -> toAdminDriver(p, owners.get(p.getUserId())));
    }

    @Transactional
    public AdminDriverResponse decideDriver(UUID adminId, UUID profileId, boolean approve, String reason) {
        DriverProfile profile = profiles.findById(profileId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ tài xế"));
        requirePending(profile.getVerificationStatus());
        profile.review(approve ? VerificationStatus.APPROVED : VerificationStatus.REJECTED, adminId, approve ? null : reason.trim());
        audit.record(adminId, approve ? "DRIVER_APPROVED" : "DRIVER_REJECTED", "DRIVER_PROFILE", profile.getId(), approve ? null : reason.trim());
        profiles.flush();
        return toAdminDriver(profile, users.findById(profile.getUserId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminVehicleResponse> pendingVehicles(Pageable pageable) {
        Page<Vehicle> page = vehicles.findByVerificationStatus(VerificationStatus.PENDING_REVIEW, pageable);
        Map<UUID, DriverProfile> owners = profiles.findAllById(page.getContent().stream().map(Vehicle::getDriverId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(DriverProfile::getId, Function.identity()));
        Map<UUID, User> ownerUsers = usersById(owners.values().stream().map(DriverProfile::getUserId).collect(Collectors.toSet()));
        return PageResponse.of(page, v -> toAdminVehicle(v, owners.get(v.getDriverId()), ownerUsers));
    }

    @Transactional
    public AdminVehicleResponse decideVehicle(UUID adminId, UUID vehicleId, boolean approve, String reason) {
        Vehicle vehicle = vehicles.findById(vehicleId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phương tiện"));
        requirePending(vehicle.getVerificationStatus());
        vehicle.review(approve ? VerificationStatus.APPROVED : VerificationStatus.REJECTED, adminId, approve ? null : reason.trim());
        audit.record(adminId, approve ? "VEHICLE_APPROVED" : "VEHICLE_REJECTED", "VEHICLE", vehicle.getId(), approve ? null : reason.trim());
        vehicles.flush();
        DriverProfile owner = profiles.findById(vehicle.getDriverId()).orElse(null);
        return toAdminVehicle(vehicle, owner, owner == null ? Map.of() : usersById(Set.of(owner.getUserId())));
    }

    private static void requirePending(VerificationStatus status) {
        if (status != VerificationStatus.PENDING_REVIEW) throw new ConflictException("Chỉ có thể duyệt hoặc từ chối hồ sơ đang chờ duyệt");
    }

    private Map<UUID, User> usersById(Set<UUID> ids) {
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static AdminDriverResponse toAdminDriver(DriverProfile profile, User owner) {
        return new AdminDriverResponse(DriverProfileResponse.from(profile), owner == null ? null : owner.getFullName(),
                owner == null ? null : owner.getEmail(), owner == null ? null : owner.getPhone());
    }

    private static AdminVehicleResponse toAdminVehicle(Vehicle vehicle, DriverProfile owner, Map<UUID, User> users) {
        User user = owner == null ? null : users.get(owner.getUserId());
        return new AdminVehicleResponse(VehicleResponse.from(vehicle), user == null ? null : user.getFullName(), owner == null ? null : owner.getVerificationStatus());
    }
}
