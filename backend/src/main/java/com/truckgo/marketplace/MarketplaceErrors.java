package com.truckgo.marketplace;

import com.truckgo.common.exception.BusinessRuleException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** Stable error codes of the marketplace, returned as {@code data.code}. */
final class MarketplaceErrors {
    static final String SHIPMENT_ALREADY_ASSIGNED = "SHIPMENT_ALREADY_ASSIGNED";
    static final String SHIPMENT_NOT_AVAILABLE = "SHIPMENT_NOT_AVAILABLE";
    static final String APPLICATION_NOT_PENDING = "APPLICATION_NOT_PENDING";
    static final String DRIVER_NOT_ELIGIBLE = "DRIVER_NOT_ELIGIBLE";
    static final String VEHICLE_NOT_ELIGIBLE = "VEHICLE_NOT_ELIGIBLE";
    static final String ALREADY_APPLIED = "ALREADY_APPLIED";
    static final String APPLICATION_REJECTED_BEFORE = "APPLICATION_REJECTED_BEFORE";
    static final String APPLICATION_LIMIT_REACHED = "APPLICATION_LIMIT_REACHED";

    private MarketplaceErrors() { }

    static BusinessRuleException alreadyAssigned() {
        return BusinessRuleException.conflict(SHIPMENT_ALREADY_ASSIGNED, "Đơn đã có tài xế nhận");
    }

    static BusinessRuleException notAvailable(String why) {
        return BusinessRuleException.conflict(SHIPMENT_NOT_AVAILABLE, why);
    }

    static BusinessRuleException notPending() {
        return BusinessRuleException.conflict(APPLICATION_NOT_PENDING, "Yêu cầu nhận đơn không còn ở trạng thái chờ");
    }

    static BusinessRuleException driverNotEligible(String why) {
        return new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, DRIVER_NOT_ELIGIBLE, why);
    }

    static BusinessRuleException vehicleNotEligible(List<String> reasons) {
        return new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, VEHICLE_NOT_ELIGIBLE, "Xe không đáp ứng yêu cầu của đơn: " + String.join("; ", reasons),
                Map.of("reasons", reasons));
    }
}
