package com.truckgo.driver;

import com.truckgo.common.domain.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DriverProfileTest {
    @Test void newProfileIsNotApproved() {
        assertThat(new DriverProfile(UUID.randomUUID(), "012345678901", "C").getVerificationStatus()).isEqualTo(VerificationStatus.NOT_SUBMITTED);
    }

    @Test void changingLicenceOfApprovedProfileRequiresReReview() {
        DriverProfile profile = new DriverProfile(UUID.randomUUID(), "012345678901", "C");
        profile.markSubmitted();
        profile.review(VerificationStatus.APPROVED, UUID.randomUUID(), null);
        profile.updateLicense("012345678901", "C");
        assertThat(profile.getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        profile.updateLicense("012345678901", "CE");
        assertThat(profile.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING_REVIEW);
        assertThat(profile.getReviewedBy()).isNull();
    }
}
