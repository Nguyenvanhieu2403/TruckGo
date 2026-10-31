package com.truckgo.trip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TrackingSessionRepository extends JpaRepository<TrackingSession, UUID> { }
