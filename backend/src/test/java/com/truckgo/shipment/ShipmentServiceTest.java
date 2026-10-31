package com.truckgo.shipment;

import com.truckgo.common.exception.ConflictException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.geo.NoGeocodingProvider;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShipmentServiceTest {
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final ShipmentAttachmentRepository attachments = mock(ShipmentAttachmentRepository.class);
    private final ShipmentStatusHistoryRepository history = mock(ShipmentStatusHistoryRepository.class);
    private final ShipmentCodeGenerator codes = mock(ShipmentCodeGenerator.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    private final ShipmentProperties properties = new ShipmentProperties(ZoneId.of("Asia/Ho_Chi_Minh"), Duration.ofMinutes(15), 10, 50, 10, null, List.of());
    private final ShipmentService service = new ShipmentService(shipments, attachments, history,
            new ShipmentDraftAssembler(ShipmentTestData.catalogs(), new NoGeocodingProvider(), clock), new ShipmentMapper(ShipmentTestData.catalogs()),
            codes, mock(ShipmentAttachmentCleanup.class), properties, clock, mock(org.springframework.context.ApplicationEventPublisher.class));
    private final UUID customerA = UUID.randomUUID();
    private final UUID customerB = UUID.randomUUID();

    @BeforeEach void setUp() {
        when(shipments.saveAndFlush(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attachments.findByShipmentIdOrderByCreatedAt(any())).thenReturn(List.of());
        when(history.findByShipmentIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    @Test void createsDraftWithInitialHistoryAndRetriesOnCodeCollision() {
        when(codes.next()).thenReturn("TG261101-TAKEN1", "TG261101-FREE22");
        when(shipments.existsByShipmentCode("TG261101-TAKEN1")).thenReturn(true);
        var detail = service.createDraft(customerA, ShipmentTestData.validDraft());
        assertThat(detail.shipmentCode()).isEqualTo("TG261101-FREE22");
        assertThat(detail.status()).isEqualTo(ShipmentStatus.DRAFT);
        assertThat(detail.customerId()).isEqualTo(customerA);
        assertThat(detail.priceStatus().name()).isEqualTo("NOT_QUOTED");
        assertThat(detail.estimatedPrice()).isNull();
        verify(codes, times(2)).next();
        verify(history).save(any(ShipmentStatusHistory.class));
    }

    @Test void otherCustomersOrdersAreNotFound() {
        Shipment shipment = owned(customerA);
        assertThatThrownBy(() -> service.get(new AuthenticatedUser(customerB, UserRole.CUSTOMER, UUID.randomUUID()), shipment.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.updateDraft(customerB, shipment.getId(), ShipmentTestData.validDraft())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(customerB, shipment.getId(), "Không cần nữa")).isInstanceOf(ResourceNotFoundException.class);
        var admin = new AuthenticatedUser(UUID.randomUUID(), UserRole.ADMIN, UUID.randomUUID());
        assertThat(service.get(admin, shipment.getId()).actions().editable()).as("admins view, but cannot act as owner").isFalse();
    }

    @Test void publishTwiceIsRejected() {
        Shipment shipment = owned(customerA);
        service.publish(customerA, shipment.getId());
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PUBLISHED);
        assertThat(shipment.getPublishedAt()).isEqualTo(clock.instant());
        assertThatThrownBy(() -> service.publish(customerA, shipment.getId())).isInstanceOf(ConflictException.class).hasMessageContaining("đã được đăng");
        verify(history, times(1)).save(any());
    }

    @Test void completedOrdersCannotBeCancelledAndPublishedCannotBeDeleted() {
        Shipment completed = owned(customerA);
        ReflectionTestUtils.setField(completed, "status", ShipmentStatus.COMPLETED);
        assertThatThrownBy(() -> service.cancel(customerA, completed.getId(), "Đổi ý rồi")).isInstanceOf(ConflictException.class);
        Shipment published = owned(customerA);
        ReflectionTestUtils.setField(published, "status", ShipmentStatus.PUBLISHED);
        assertThatThrownBy(() -> service.deleteDraft(customerA, published.getId())).isInstanceOf(ConflictException.class);
        verify(history, never()).save(any());
    }

    @Test void updateRequiresCurrentVersion() {
        Shipment shipment = owned(customerA);
        var stale = ShipmentTestData.validDraft();
        assertThatThrownBy(() -> service.updateDraft(customerA, shipment.getId(), stale)).isInstanceOf(IllegalArgumentException.class);
        ReflectionTestUtils.setField(shipment, "version", 3L);
        var withOldVersion = new com.truckgo.shipment.dto.ShipmentRequests.DraftRequest(stale.pickupTime(), stale.deliveryDeadline(), null, null, 1, null, null, null, null,
                null, stale.stops(), stale.items(), stale.services(), 2L);
        assertThatThrownBy(() -> service.updateDraft(customerA, shipment.getId(), withOldVersion)).isInstanceOf(ConflictException.class);
    }

    private Shipment owned(UUID owner) {
        when(codes.next()).thenReturn("TG261101-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        when(shipments.existsByShipmentCode(anyString())).thenReturn(false);
        Shipment shipment = new Shipment(codes.next(), owner);
        new ShipmentDraftAssembler(ShipmentTestData.catalogs(), new NoGeocodingProvider(), clock).apply(shipment, ShipmentTestData.validDraft());
        when(shipments.findById(shipment.getId())).thenReturn(Optional.of(shipment));
        when(shipments.findByIdForUpdate(shipment.getId())).thenReturn(Optional.of(shipment));
        return shipment;
    }
}
