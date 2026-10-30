package com.truckgo.security;

import com.truckgo.user.UserRole;

import java.util.UUID;

/** Principal of an authenticated request. Identity always comes from the verified token and the database, never from request bodies. */
public record AuthenticatedUser(UUID id, UserRole role, UUID sessionFamilyId) { }
