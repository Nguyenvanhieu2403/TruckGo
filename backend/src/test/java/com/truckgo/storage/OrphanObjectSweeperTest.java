package com.truckgo.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrphanObjectSweeperTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private final StorageService storage = mock(StorageService.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StorageDeletionQueue queue = mock(StorageDeletionQueue.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private OrphanObjectSweeper sweeper;

    @BeforeEach void setUp() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        StorageDeletionProperties props = new StorageDeletionProperties(Duration.ofMinutes(1), 100, 20, Duration.ofHours(6), Duration.ofMinutes(5),
                true, true, Duration.ofHours(24), 1000, 3, 0.5);
        sweeper = new OrphanObjectSweeper(storage, jdbc, queue, props, tx, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StorageService.StoredObject obj(String key, Duration age) { return new StorageService.StoredObject(key, NOW.minus(age), 100); }

    private void known(List<String> photoKeys) {
        when(jdbc.queryForList(eq("SELECT file_key FROM shipment_attachments WHERE file_key IN (:keys)"), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(new ArrayList<>(photoKeys));
        when(jdbc.queryForList(eq("SELECT file_key FROM storage_deletion_tasks WHERE status = 'PENDING' AND file_key IN (:keys)"), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(new ArrayList<>());
    }

    @Test void recentObjectsAndObjectsWithMetadataAreKept() {
        when(storage.list(eq("shipments/"), isNull(), anyInt())).thenReturn(List.of(
                obj("shipments/a/live.png", Duration.ofDays(3)), obj("shipments/a/orphan.png", Duration.ofDays(3)), obj("shipments/a/new.png", Duration.ofMinutes(5))));
        known(List.of("shipments/a/live.png"));
        OrphanObjectSweeper.Report report = sweeper.sweep(false);
        assertThat(report.candidates()).isEqualTo(2);
        assertThat(report.orphans()).isEqualTo(1);
        verify(queue).enqueue(List.of("shipments/a/orphan.png"), StorageDeletionTask.Reason.ORPHAN, null);
    }

    @Test void dryRunOnlyReports() {
        when(storage.list(anyString(), isNull(), anyInt())).thenReturn(List.of(obj("shipments/a/orphan.png", Duration.ofDays(3))));
        known(List.of());
        assertThat(sweeper.sweep(true).orphans()).isEqualTo(1);
        verify(queue, never()).enqueue(any(), any(), any());
    }

    @Test void aDatabaseErrorNeverMeansNotFound() {
        when(storage.list(anyString(), isNull(), anyInt())).thenReturn(List.of(obj("shipments/a/x.png", Duration.ofDays(3))));
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class))).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThat(sweeper.sweep(false).stoppedBecause()).isEqualTo("DATABASE_UNAVAILABLE");
        verify(queue, never()).enqueue(any(), any(), any());
    }

    @Test void storageOutageSkipsTheRun() {
        when(storage.list(anyString(), isNull(), anyInt())).thenThrow(new StorageUnavailableException("down", null));
        assertThat(sweeper.sweep(false).stoppedBecause()).isEqualTo("STORAGE_UNAVAILABLE");
    }

    @Test void aSuspiciousShareOfOrphansStopsDeletion() {
        List<StorageService.StoredObject> objects = IntStream.range(0, 30).mapToObj(i -> obj("shipments/a/" + i + ".png", Duration.ofDays(3))).toList();
        when(storage.list(anyString(), isNull(), anyInt())).thenReturn(objects);
        known(List.of("shipments/a/0.png"));   // 29 of 30 look orphaned: wrong database or bucket
        assertThat(sweeper.sweep(false).stoppedBecause()).isEqualTo("RATIO_EXCEEDED");
        verify(queue, never()).enqueue(any(), any(), any());
    }

    @Test void deletionsPerRunAreCapped() {
        List<StorageService.StoredObject> objects = IntStream.range(0, 10).mapToObj(i -> obj("shipments/a/" + i + ".png", Duration.ofDays(3))).toList();
        when(storage.list(anyString(), isNull(), anyInt())).thenReturn(objects);
        known(List.of("shipments/a/0.png", "shipments/a/1.png", "shipments/a/2.png", "shipments/a/3.png", "shipments/a/4.png", "shipments/a/5.png"));
        assertThat(sweeper.sweep(false).queued()).isEqualTo(3);   // 4 orphans, at most 3 per run
    }
}
