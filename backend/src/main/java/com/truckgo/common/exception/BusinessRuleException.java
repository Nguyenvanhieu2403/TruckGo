package com.truckgo.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Business rule violation with a stable machine-readable code, returned as {@code data: {"code": "..."}}. */
public class BusinessRuleException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public BusinessRuleException(HttpStatus status, String code, String message) { this(status, code, message, Map.of()); }

    public BusinessRuleException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public static BusinessRuleException conflict(String code, String message) { return new BusinessRuleException(HttpStatus.CONFLICT, code, message); }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, Object> details() { return details; }
}
