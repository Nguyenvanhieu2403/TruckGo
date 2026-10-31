package com.truckgo.trip;

import com.truckgo.common.exception.BusinessRuleException;
import org.springframework.http.HttpStatus;

/** Stable error codes of trip execution, returned as {@code data.code}. */
public final class TripErrors {
    public static final String INVALID_TRIP_STATUS = "INVALID_TRIP_STATUS";
    public static final String STOP_OUT_OF_ORDER = "STOP_OUT_OF_ORDER";
    public static final String STOPS_INCOMPLETE = "STOPS_INCOMPLETE";
    public static final String PHOTO_REQUIRED = "PHOTO_REQUIRED";
    public static final String OTP_INVALID = "OTP_INVALID";
    public static final String OTP_EXPIRED = "OTP_EXPIRED";
    public static final String OTP_LOCKED = "OTP_LOCKED";
    public static final String OTP_RESEND_TOO_SOON = "OTP_RESEND_TOO_SOON";
    public static final String OTP_RESEND_LIMIT = "OTP_RESEND_LIMIT";
    public static final String TRACKING_NOT_ACTIVE = "TRACKING_NOT_ACTIVE";
    public static final String LOCATION_INVALID_TIME = "LOCATION_INVALID_TIME";
    public static final String LOCATION_IMPLAUSIBLE = "LOCATION_IMPLAUSIBLE";
    public static final String LOCATION_RATE_LIMITED = "LOCATION_RATE_LIMITED";

    private TripErrors() { }

    static BusinessRuleException status(String expected, Object actual) {
        return BusinessRuleException.conflict(INVALID_TRIP_STATUS, "Chuyến đang ở trạng thái " + actual + ", cần ở trạng thái " + expected + " để thực hiện thao tác này");
    }

    static BusinessRuleException unprocessable(String code, String message) {
        return new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
