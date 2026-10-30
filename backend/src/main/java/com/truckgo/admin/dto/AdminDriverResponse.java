package com.truckgo.admin.dto;

import com.truckgo.driver.dto.DriverProfileResponse;

/** Pending driver profile with just enough account data for an admin to identify the applicant. */
public record AdminDriverResponse(DriverProfileResponse profile, String fullName, String email, String phone) { }
