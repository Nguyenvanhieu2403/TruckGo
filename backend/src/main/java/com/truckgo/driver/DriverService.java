package com.truckgo.driver;

import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.common.exception.ConflictException;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.driver.dto.DriverProfileResponse;
import com.truckgo.driver.dto.UpdateDriverProfileRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DriverService {
    private final DriverProfileRepository profiles;
    private final DriverLicenseValidator licenseValidator;

    public DriverService(DriverProfileRepository profiles, DriverLicenseValidator licenseValidator) {
        this.profiles = profiles;
        this.licenseValidator = licenseValidator;
    }

    @Transactional(readOnly = true)
    public DriverProfileResponse getMine(UUID userId) { return DriverProfileResponse.from(requireProfile(userId)); }

    @Transactional
    public DriverProfileResponse updateMine(UUID userId, UpdateDriverProfileRequest request) {
        DriverProfile profile = requireProfile(userId);
        if (profile.getVerificationStatus() == VerificationStatus.PENDING_REVIEW)
            throw new ConflictException("Hồ sơ đang chờ duyệt, không thể chỉnh sửa lúc này");
        DriverLicenseValidator.License license = licenseValidator.normalize(request.licenseNumber(), request.licenseClass());
        if (profiles.existsByLicenseNumberAndIdNot(license.number(), profile.getId()))
            throw FieldErrorException.conflict("licenseNumber", "Số giấy phép lái xe đã được đăng ký");
        profile.updateLicense(license.number(), license.licenseClass());
        return DriverProfileResponse.from(profile);
    }

    @Transactional
    public DriverProfileResponse submitForVerification(UUID userId) {
        DriverProfile profile = requireProfile(userId);
        switch (profile.getVerificationStatus()) {
            case PENDING_REVIEW -> throw new ConflictException("Hồ sơ đã được gửi và đang chờ duyệt");
            case APPROVED -> throw new ConflictException("Hồ sơ đã được duyệt");
            default -> { }
        }
        // Re-validate against the current business rules before it reaches an admin.
        licenseValidator.normalize(profile.getLicenseNumber(), profile.getLicenseClass());
        profile.markSubmitted();
        return DriverProfileResponse.from(profile);
    }

    public DriverProfile requireProfile(UUID userId) {
        return profiles.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ tài xế"));
    }
}
