package com.truckgo.common.domain;

/** Review lifecycle shared by driver profiles and vehicles. Only an ADMIN may move an item to APPROVED or REJECTED. */
public enum VerificationStatus { NOT_SUBMITTED, PENDING_REVIEW, APPROVED, REJECTED }
