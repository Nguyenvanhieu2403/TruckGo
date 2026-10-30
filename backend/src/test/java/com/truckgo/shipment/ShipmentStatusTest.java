package com.truckgo.shipment;

import com.truckgo.common.exception.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentStatusTest {
    @Test void sprintThreeTransitions() {
        assertThat(ShipmentStatus.DRAFT.canTransitionTo(ShipmentStatus.PUBLISHED)).isTrue();
        assertThat(ShipmentStatus.DRAFT.canTransitionTo(ShipmentStatus.CANCELLED)).isTrue();
        assertThat(ShipmentStatus.PUBLISHED.canTransitionTo(ShipmentStatus.CANCELLED)).isTrue();
    }

    @Test void forbiddenTransitions() {
        assertThat(ShipmentStatus.CANCELLED.canTransitionTo(ShipmentStatus.PUBLISHED)).isFalse();
        assertThat(ShipmentStatus.COMPLETED.canTransitionTo(ShipmentStatus.CANCELLED)).isFalse();
        assertThat(ShipmentStatus.PUBLISHED.canTransitionTo(ShipmentStatus.COMPLETED)).isFalse();
        assertThat(ShipmentStatus.PUBLISHED.canTransitionTo(ShipmentStatus.PUBLISHED)).isFalse();
        assertThat(ShipmentStatus.DRAFT.canTransitionTo(ShipmentStatus.DELIVERED)).isFalse();
        assertThat(ShipmentStatus.COMPLETED.isCancellableByCustomer()).isFalse();
        assertThat(ShipmentStatus.ASSIGNED.isCancellableByCustomer()).isFalse();
    }

    @Test void entityRejectsIllegalTransition() {
        Shipment shipment = new Shipment("TG000000-AAAAA", UUID.randomUUID());
        shipment.markCancelled(Instant.now(), "Thay đổi kế hoạch");
        assertThatThrownBy(() -> shipment.markPublished(Instant.now())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> shipment.markDeleted(Instant.now())).isInstanceOf(ConflictException.class);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }
}
