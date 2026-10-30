package com.truckgo.user;

/**
 * PENDING_VERIFICATION is reserved for the contact-verification flow (OTP/email link) planned for a later sprint;
 * only ACTIVE accounts may log in or call authenticated APIs.
 */
public enum UserStatus { PENDING_VERIFICATION, ACTIVE, SUSPENDED, LOCKED }
