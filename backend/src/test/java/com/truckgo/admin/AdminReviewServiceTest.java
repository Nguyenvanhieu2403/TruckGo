package com.truckgo.admin;

import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.common.exception.ConflictException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverProfileRepository;
import com.truckgo.user.UserRepository;
import com.truckgo.vehicle.VehicleRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminReviewServiceTest {
    private final DriverProfileRepository profiles = mock(DriverProfileRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminReviewService service = new AdminReviewService(profiles, mock(VehicleRepository.class), users, audit);
    private final UUID adminId = UUID.randomUUID();

    @Test void approvesPendingProfileAndRecordsReviewer() {
        DriverProfile profile = new DriverProfile(UUID.randomUUID(), "012345678901", "C");
        profile.markSubmitted();
        when(profiles.findById(profile.getId())).thenReturn(Optional.of(profile));
        service.decideDriver(adminId, profile.getId(), true, null);
        assertThat(profile.getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(profile.getReviewedBy()).isEqualTo(adminId);
        assertThat(profile.getReviewedAt()).isNotNull();
        verify(audit).record(eq(adminId), eq("DRIVER_APPROVED"), eq("DRIVER_PROFILE"), eq(profile.getId()), any());
    }

    @Test void onlyPendingProfilesCanBeDecided() {
        DriverProfile profile = new DriverProfile(UUID.randomUUID(), "012345678901", "C");
        when(profiles.findById(profile.getId())).thenReturn(Optional.of(profile));
        assertThatThrownBy(() -> service.decideDriver(adminId, profile.getId(), true, null)).isInstanceOf(ConflictException.class);
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test void unknownProfileIsNotFound() {
        assertThatThrownBy(() -> service.decideDriver(adminId, UUID.randomUUID(), false, "Giấy phép mờ")).isInstanceOf(ResourceNotFoundException.class);
    }
}
