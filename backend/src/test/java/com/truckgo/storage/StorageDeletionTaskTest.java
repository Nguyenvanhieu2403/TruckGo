package com.truckgo.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StorageDeletionTaskTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test void retriesWithCappedExponentialBackoffThenGivesUp() {
        StorageDeletionTask t = new StorageDeletionTask("shipments/a/b.png", StorageDeletionTask.Reason.ATTACHMENT_DELETED, null, NOW);
        t.failed(NOW, "StorageUnavailableException", 4, Duration.ofMinutes(3));
        assertThat(t.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        t.failed(NOW, "x", 4, Duration.ofMinutes(3));
        assertThat(t.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        t.failed(NOW, "x", 4, Duration.ofMinutes(3));
        assertThat(t.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));   // capped
        assertThat(t.getStatus()).isEqualTo(StorageDeletionTask.Status.PENDING);
        t.failed(NOW, "x", 4, Duration.ofMinutes(3));
        assertThat(t.getStatus()).isEqualTo(StorageDeletionTask.Status.FAILED);
        assertThat(t.getAttempts()).isEqualTo(4);
    }
}
