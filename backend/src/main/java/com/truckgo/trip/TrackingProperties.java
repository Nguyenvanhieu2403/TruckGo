package com.truckgo.trip;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.tracking.*: GPS ingestion limits and freshness.
 * <ul>
 *   <li>minInterval: fixes closer together than this (per trip) are refused with 429.</li>
 *   <li>liveThreshold / staleThreshold: a latest fix younger than liveThreshold is LIVE, younger than staleThreshold
 *       STALE, otherwise OFFLINE (no position is presented as current).</li>
 *   <li>maxFutureSkew / maxAge: accepted range of the device timestamp relative to the server clock.</li>
 *   <li>maxSpeedKmh: a jump implying a faster speed than this from the previous fix is refused.</li>
 *   <li>movingInterval / idleInterval / minDistanceMeters: what the driver app is told to use.</li>
 *   <li>retention: fixes of finished trips older than this are deleted.</li>
 * </ul>
 */
@ConfigurationProperties("app.tracking")
public record TrackingProperties(Duration minInterval, Duration liveThreshold, Duration staleThreshold, Duration maxFutureSkew, Duration maxAge,
                                 double maxSpeedKmh, Duration movingInterval, Duration idleInterval, int minDistanceMeters, Duration latestTtl,
                                 Duration retention, Duration streamTimeout, Duration heartbeat) { }
