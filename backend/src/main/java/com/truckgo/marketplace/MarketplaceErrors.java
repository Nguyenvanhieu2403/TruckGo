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
    static final String SCHEDULE_CONFLICT = "SCHEDULE_CONFLICT";
    static final String DRIVER_ALREADY_ON_SHIPMENT = "DRIVER_ALREADY_ON_SHIPMENT";
    static final String VEHICLE_ALREADY_ON_SHIPMENT = "VEHICLE_ALREADY_ON_SHIPMENT";
    static final String ASSIGNMENT_NOT_ACTIVE = "ASSIGNMENT_NOT_ACTIVE";
    static final String TRIP_ALREADY_STARTED = "TRIP_ALREADY_STARTED";

    private MarketplaceErrors() { }

    static BusinessRuleException alreadyAssigned() {
        return BusinessRuleException.conflict(SHIPMENT_ALREADY_ASSIGNED, "Đơn đã có tài xế nhận");
    }

    /** Every vehicle slot of the order is taken (single-vehicle orders keep the original message). */
    static BusinessRuleException fullyAssigned(int vehicleCount) {
        return vehicleCount > 1 ? BusinessRuleException.conflict(SHIPMENT_ALREADY_ASSIGNED, "Đơn đã đủ " + vehicleCount + " xe") : alreadyAssigned();
    }

    static BusinessRuleException scheduleConflict(List<String> reasons) {
        return new BusinessRuleException(HttpStatus.CONFLICT, SCHEDULE_CONFLICT, "Trùng lịch: " + String.join("; ", reasons), Map.of("reasons", reasons));
    }

    static BusinessRuleException tripStarted(Object status) {
        return BusinessRuleException.conflict(TRIP_ALREADY_STARTED, "Chuyến đã bắt đầu (" + status + "), không thể gỡ hoặc thay tài xế. "
                + "Hãy báo sự cố và xử lý theo quy trình vận chuyển");
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
