package com.truckgo.auth.dto;

public interface AccountRegistration {
    String fullName();
    String email();
    String phone();
    String password();

    static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
