package com.truckgo.user;

import java.nio.charset.StandardCharsets;

/** 8–72 bytes (BCrypt truncates beyond 72), with at least one letter and one digit. */
public final class PasswordPolicy {
    public static final String MESSAGE = "Mật khẩu phải dài 8–72 ký tự và chứa cả chữ lẫn số";

    private PasswordPolicy() { }

    public static boolean isValid(String password) {
        if (password == null || password.length() < 8) return false;
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) return false;
        return password.chars().anyMatch(Character::isLetter) && password.chars().anyMatch(Character::isDigit);
    }
}
