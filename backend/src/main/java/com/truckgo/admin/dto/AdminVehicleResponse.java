package com.truckgo.admin.dto;

import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.vehicle.dto.VehicleResponse;

public record AdminVehicleResponse(VehicleResponse vehicle, String driverName, VerificationStatus driverVerificationStatus) { }
