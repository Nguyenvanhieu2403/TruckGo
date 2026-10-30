package com.truckgo.auth.dto;

import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.user.dto.UserResponse;

public record RegistrationResponse(UserResponse user, VerificationStatus driverVerificationStatus) { }
