package com.truckgo.user;

public enum UserRole {
    CUSTOMER, DRIVER, ADMIN;
    public String authority() { return "ROLE_" + name(); }
}
