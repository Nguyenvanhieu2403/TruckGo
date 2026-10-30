package com.truckgo.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Business rule violation tied to specific request fields, returned as {@code data: {field: message}}. */
public class FieldErrorException extends RuntimeException {
    private final HttpStatus status;
    private final Map<String, String> errors;

    public FieldErrorException(HttpStatus status, String message, Map<String, String> errors) {
        super(message);
        this.status = status;
        this.errors = Map.copyOf(errors);
    }

    public static FieldErrorException invalid(String field, String message) {
        return new FieldErrorException(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ", Map.of(field, message));
    }

    public static FieldErrorException conflict(String field, String message) {
        return new FieldErrorException(HttpStatus.CONFLICT, message, Map.of(field, message));
    }

    public HttpStatus status() { return status; }
    public Map<String, String> errors() { return errors; }
}
