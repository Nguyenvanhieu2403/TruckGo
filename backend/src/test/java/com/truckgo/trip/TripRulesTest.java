package com.truckgo.trip;

import com.truckgo.common.exception.BusinessRuleException;
import com.truckgo.common.exception.ConflictException;
import com.truckgo.security.SecurityProperties;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.trip.dto.TripDtos.TrackingState;
import com.truckgo.trip.dto.TripDtos.TrackingView;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TripRulesTest {
    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");
    private static final TrackingProperties TRACKING = new TrackingProperties(Duration.ofSeconds(3), Duration.ofSeconds(60), Duration.ofMinutes(5),
            Duration.ofMinutes(2), Duration.ofMinutes(10), 200, Duration.ofSeconds(8), Duration.ofSeconds(30), 25, Duration.ofMinutes(30),
            Duration.ofDays(90), Duration.ofMinutes(10), Duration.ofSeconds(20));

    private static Shipment shipment(ShipmentStatus status) {
        Shipment s = new Shipment("TG261101-ABCDEF", UUID.randomUUID());
        ReflectionTestUtils.setField(s, "status", status);
        ReflectionTestUtils.setField(s, "assignedDriverId", UUID.randomUUID());
        return s;
    }

    // ------------------------------------------------------------------ status machine

    @Test void tripStatusesAdvanceOneStepAtATime() {
        Shipment s = shipment(ShipmentStatus.ASSIGNED);
        s.markDriverArrived(NOW);
        s.markPickedUp(NOW.plusSeconds(60));
        s.markInTransit(NOW.plusSeconds(120));
        s.markDelivered(NOW.plusSeconds(3600));
        s.markCompleted(NOW.plusSeconds(3700));
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.COMPLETED);
        assertThat(s.getDriverArrivedAt()).isEqualTo(NOW);
        assertThat(s.getCompletedAt()).isEqualTo(NOW.plusSeconds(3700));
    }

    @Test void skippingAStatusIsRefused() {
        assertThatThrownBy(() -> shipment(ShipmentStatus.ASSIGNED).markCompleted(NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> shipment(ShipmentStatus.ASSIGNED).markDelivered(NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> shipment(ShipmentStatus.DRIVER_ARRIVED).markInTransit(NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> shipment(ShipmentStatus.PICKED_UP).markDelivered(NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> shipment(ShipmentStatus.COMPLETED).markDelivered(NOW)).isInstanceOf(ConflictException.class);
    }

    @Test void onlyTheAssignedDriverOwnsTheTrip() {
        Shipment s = shipment(ShipmentStatus.ASSIGNED);
        UUID driver = s.getAssignedDriverId();
        assertThat(s.isAssignedTo(driver)).isTrue();
        assertThat(s.isAssignedTo(UUID.randomUUID())).isFalse();
    }

    @Test void stopsMoveFromPendingToArrivedToCompleted() {
        StopProgress stop = new StopProgress(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2);
        stop.arrive(NOW, "Đến", null);
        assertThatThrownBy(() -> stop.arrive(NOW, null, null)).isInstanceOf(BusinessRuleException.class);
        stop.complete(NOW.plusSeconds(600), "Xong", new GeoFix(new BigDecimal("21.0285"), new BigDecimal("105.8542")));
        assertThat(stop.getStatus()).isEqualTo(StopStatus.COMPLETED);
        assertThat(stop.isDone()).isTrue();
        assertThatThrownBy(() -> stop.complete(NOW, null, null)).isInstanceOf(BusinessRuleException.class);
    }

    @Test void incidentsFollowTheirOwnLifecycle() {
        assertThat(IncidentStatus.OPEN.canMoveTo(IncidentStatus.IN_PROGRESS)).isTrue();
        assertThat(IncidentStatus.IN_PROGRESS.canMoveTo(IncidentStatus.RESOLVED)).isTrue();
        assertThat(IncidentStatus.RESOLVED.canMoveTo(IncidentStatus.OPEN)).isFalse();
        assertThat(IncidentStatus.CANCELLED.canMoveTo(IncidentStatus.RESOLVED)).isFalse();
    }

    // ------------------------------------------------------------------ coordinates

    @Test void coordinatesAreValidatedAndRounded() {
        assertThatThrownBy(() -> new GeoFix(new BigDecimal("90.1"), BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoFix(BigDecimal.ZERO, new BigDecimal("-180.5"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeoFix.optional(BigDecimal.ONE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(GeoFix.optional(null, null)).isNull();
        assertThat(new GeoFix(new BigDecimal("21.02851234"), new BigDecimal("105.8542")).latitude()).isEqualByComparingTo("21.028512");
        // Hà Nội – Hải Phòng is about 100 km as the crow flies.
        double km = new GeoFix(new BigDecimal("21.0285"), new BigDecimal("105.8542")).metersTo(new GeoFix(new BigDecimal("20.8449"), new BigDecimal("106.6881"))) / 1000;
        assertThat(km).isBetween(85.0, 100.0);
    }

    // ------------------------------------------------------------------ OTP

    private static OtpCodec codec(String secret) {
        TripProperties trip = new TripProperties(Duration.ofMinutes(30), 5, Duration.ofSeconds(60), 5, secret, false, 20);
        return new OtpCodec(trip, new SecurityProperties(new SecurityProperties.Jwt(null, "test", Duration.ofMinutes(15)), null, null, java.util.List.of(), 4));
    }

    @Test void otpCodesAreSixRandomDigitsAndOnlyTheirHmacIsKept() {
        OtpCodec codec = codec("unit-test-secret");
        UUID id = UUID.randomUUID();
        String code = codec.newCode();
        assertThat(code).matches("\\d{6}");
        String hash = codec.hash(id, code);
        assertThat(hash).hasSize(64).doesNotContain(code);
        assertThat(codec.matches(id, code, hash)).isTrue();
        assertThat(codec.matches(UUID.randomUUID(), code, hash)).isFalse();     // bound to its confirmation
        assertThat(codec.matches(id, code.equals("000000") ? "111111" : "000000", hash)).isFalse();
        assertThat(codec.matches(id, "12345", hash)).isFalse();
        assertThat(codec.matches(id, null, hash)).isFalse();
        assertThat(codec("other-secret").matches(id, code, hash)).isFalse();    // a dump without the key is useless
    }

    @Test void wrongGuessesLockTheCodeAndExpiryIsEnforced() {
        DeliveryConfirmation c = new DeliveryConfirmation(UUID.randomUUID(), "x", NOW.plusSeconds(1800), 3, NOW);
        assertThat(c.fail()).isFalse();
        assertThat(c.fail()).isFalse();
        assertThat(c.attemptsLeft()).isEqualTo(1);
        assertThat(c.fail()).isTrue();
        assertThat(c.getStatus()).isEqualTo(DeliveryConfirmation.Status.LOCKED);
        assertThat(c.isExpiredAt(NOW.plusSeconds(1799))).isFalse();
        assertThat(c.isExpiredAt(NOW.plusSeconds(1800))).isTrue();
        c.reissue("y", NOW.plusSeconds(3600), NOW.plusSeconds(120));
        assertThat(c.getStatus()).isEqualTo(DeliveryConfirmation.Status.PENDING);
        assertThat(c.attemptsLeft()).isEqualTo(3);
        assertThat(c.getSendCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ tracking freshness

    private static TrackingService tracking() {
        return new TrackingService(null, null, null, null, null, null, mock(TripEventHub.class), TRACKING, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TrackingService.Latest fixAgo(long seconds) {
        return new TrackingService.Latest(new BigDecimal("21.028500"), new BigDecimal("105.854200"), BigDecimal.TEN, new BigDecimal("40"), new BigDecimal("90"),
                NOW.minusSeconds(seconds), NOW.minusSeconds(seconds));
    }

    private static Optional<TrackingSession> session(boolean paused) {
        TrackingSession s = new TrackingSession(UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(3600));
        if (paused) s.pause(NOW);
        return Optional.of(s);
    }

    @Test void freshnessIsDerivedFromTheFixAgeNeverAssumed() {
        Shipment s = shipment(ShipmentStatus.IN_TRANSIT);
        TrackingService t = tracking();
        assertThat(t.view(s, Optional.of(fixAgo(10)), session(false)).state()).isEqualTo(TrackingState.LIVE);
        assertThat(t.view(s, Optional.of(fixAgo(120)), session(false)).state()).isEqualTo(TrackingState.STALE);
        TrackingView offline = t.view(s, Optional.of(fixAgo(900)), session(false));
        assertThat(offline.state()).isEqualTo(TrackingState.OFFLINE);
        assertThat(offline.speed()).isNull();
        assertThat(offline.ageSeconds()).isEqualTo(900);
        assertThat(t.view(s, Optional.empty(), session(false)).state()).isEqualTo(TrackingState.OFFLINE);
        assertThat(t.view(s, Optional.of(fixAgo(5)), session(true)).state()).isEqualTo(TrackingState.PAUSED);
    }

    @Test void noPositionOutsideTransit() {
        TrackingService t = tracking();
        TrackingView before = t.view(shipment(ShipmentStatus.PICKED_UP), Optional.of(fixAgo(5)), Optional.empty());
        assertThat(before.state()).isEqualTo(TrackingState.NOT_STARTED);
        assertThat(before.latitude()).isNull();
        TrackingView after = t.view(shipment(ShipmentStatus.COMPLETED), Optional.of(fixAgo(5)), session(false));
        assertThat(after.state()).isEqualTo(TrackingState.ENDED);
        assertThat(after.latitude()).isNull();
    }
}
