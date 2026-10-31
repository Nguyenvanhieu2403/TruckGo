package com.truckgo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.truckgo.shipment.ShipmentService;
import com.truckgo.storage.OrphanObjectSweeper;
import com.truckgo.storage.StorageDeletionQueue;
import com.truckgo.storage.StorageService;
import com.truckgo.storage.StorageUnavailableException;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Photo storage lifecycle against real PostgreSQL and MinIO, with storage failures injected through a spy. */
@Testcontainers(disabledWithoutDocker = true)
class StorageLifecycleIntegrationTest extends IntegrationTestSupport {
    static final String BUCKET = "truckgo-storage-test";
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container
    static MinIOContainer minio = new MinIOContainer(DockerImageName.parse(ShipmentIntegrationTest.MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"));

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.provider", () -> "minio");
        registry.add("app.storage.endpoint", minio::getS3URL);
        registry.add("app.storage.access-key", minio::getUserName);
        registry.add("app.storage.secret-key", minio::getPassword);
        registry.add("app.storage.bucket", () -> BUCKET);
        registry.add("app.storage.cleanup.orphan-min-age", () -> "1s");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StorageDeletionQueue queue;
    @Autowired OrphanObjectSweeper sweeper;
    @Autowired ShipmentService shipmentService;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean StorageService storage;

    @AfterEach void restoreStorage() { reset(storage); }

    // ------------------------------------------------------------------ helpers

    static byte[] png(int size) {
        byte[] bytes = new byte[size];
        System.arraycopy(ShipmentIntegrationTest.PNG, 0, bytes, 0, ShipmentIntegrationTest.PNG.length);
        Arrays.fill(bytes, ShipmentIntegrationTest.PNG.length, size, (byte) 7);
        return bytes;
    }

    MinioClient minioClient() { return MinioClient.builder().endpoint(minio.getS3URL()).credentials(minio.getUserName(), minio.getPassword()).build(); }

    List<String> objects(String prefix) throws Exception {
        List<String> keys = new ArrayList<>();
        for (Result<Item> r : minioClient().listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(prefix).recursive(true).build())) keys.add(r.get().objectName());
        return keys;
    }

    Session customer() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, null);
        return login(email, PASSWORD);
    }

    String draft(Session customer) throws Exception {
        Map<String, Object> stop1 = new HashMap<>(Map.of("stopType", "PICKUP", "sequenceNo", 1, "contactName", "Kho", "contactPhone", "0912345678", "province", "Hà Nội", "addressDetail", "Số 1"));
        Map<String, Object> stop2 = new HashMap<>(Map.of("stopType", "DELIVERY", "sequenceNo", 2, "contactName", "Khách", "contactPhone", "0987654321", "province", "Hà Nội", "addressDetail", "Số 2"));
        Map<String, Object> body = new HashMap<>();
        body.put("pickupTime", OffsetDateTime.now(ZoneOffset.ofHours(7)).plusDays(2).truncatedTo(ChronoUnit.HOURS).toString());
        body.put("stops", List.of(stop1, stop2));
        body.put("items", List.of(Map.of("itemName", "Thùng", "category", "ELECTRONICS", "quantity", 1, "weightPerUnitKg", "10")));
        return body(mvc.perform(post("/api/v1/shipments/draft").header("Authorization", bearer(customer)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isCreated()).andReturn()).at("/data/id").asText();
    }

    ResultActions upload(Session customer, String shipmentId, byte[] bytes, String name) throws Exception {
        return mvc.perform(multipart("/api/v1/shipments/" + shipmentId + "/attachments").file(new MockMultipartFile("file", name, "image/png", bytes))
                .header("Authorization", bearer(customer)));
    }

    String uploaded(Session customer, String shipmentId) throws Exception {
        return body(upload(customer, shipmentId, png(2048), "hang.png").andExpect(status().isCreated()).andReturn()).at("/data/id").asText();
    }

    String task(String column, String shipmentId) {
        return jdbc.queryForObject("select " + column + " from storage_deletion_tasks where shipment_id = ?::uuid order by created_at desc limit 1", String.class, shipmentId);
    }

    // ------------------------------------------------------------------ tests

    @Test void cancellingAnOrderRemovesItsPhotosOnlyAfterCommit() throws Exception {
        Session customer = customer();
        String id = draft(customer);
        uploaded(customer, id);
        uploaded(customer, id);
        assertThat(objects("shipments/" + id + "/")).hasSize(2);

        // A cancellation that rolls back must not touch the photos.
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(s -> {
            shipmentService.cancel(UUID.fromString(customer.user().get("id").asText()), UUID.fromString(id), "Thử hủy rồi hoàn tác");
            s.setRollbackOnly();
        });
        assertThat(objects("shipments/" + id + "/")).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from shipment_attachments where shipment_id = ?::uuid", Long.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from storage_deletion_tasks where shipment_id = ?::uuid", Long.class, id)).isZero();

        mvc.perform(post("/api/v1/shipments/" + id + "/cancel").header("Authorization", bearer(customer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Không cần chở nữa\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from shipment_attachments where shipment_id = ?::uuid", Long.class, id)).isZero();
        assertThat(jdbc.queryForList("select status from storage_deletion_tasks where shipment_id = ?::uuid", String.class, id)).containsExactly("DONE", "DONE");
        assertThat(objects("shipments/" + id + "/")).isEmpty();
    }

    @Test void aFailedDeleteIsRetriedUntilTheObjectIsGone() throws Exception {
        Session customer = customer();
        String id = draft(customer);
        String photo = uploaded(customer, id);
        doThrow(new StorageUnavailableException("MinIO down", null)).when(storage).delete(anyString());

        // The customer's delete succeeds: metadata is gone, the object waits in the queue.
        mvc.perform(delete("/api/v1/shipments/" + id + "/attachments/" + photo).header("Authorization", bearer(customer))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from shipment_attachments where id = ?::uuid", Long.class, photo)).isZero();
        assertThat(task("status", id)).isEqualTo("PENDING");
        assertThat(task("attempts", id)).isEqualTo("1");
        assertThat(objects("shipments/" + id + "/")).hasSize(1);

        jdbc.update("update storage_deletion_tasks set next_attempt_at = now() - interval '1 second' where shipment_id = ?::uuid", id);
        queue.processDue();
        assertThat(task("attempts", id)).isEqualTo("2");   // still down: backoff, not lost

        doCallRealMethod().when(storage).delete(anyString());
        jdbc.update("update storage_deletion_tasks set next_attempt_at = now() - interval '1 second' where shipment_id = ?::uuid", id);
        queue.processDue();
        assertThat(task("status", id)).isEqualTo("DONE");
        assertThat(objects("shipments/" + id + "/")).isEmpty();
    }

    @Test void aDatabaseFailureAfterUploadLeavesNoObjectBehind() throws Exception {
        Session customer = customer();
        String id = draft(customer);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_photo_insert() RETURNS trigger AS $$
                BEGIN IF NEW.file_name = 'boom.png' THEN RAISE EXCEPTION 'simulated metadata failure'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql""");
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_photo ON shipment_attachments");
        jdbc.execute("CREATE TRIGGER trg_fail_photo BEFORE INSERT ON shipment_attachments FOR EACH ROW EXECUTE FUNCTION fail_photo_insert()");
        try {
            upload(customer, id, png(4096), "boom.png").andExpect(status().is5xxServerError());
            assertThat(jdbc.queryForObject("select count(*) from shipment_attachments where shipment_id = ?::uuid", Long.class, id)).isZero();
            assertThat(objects("shipments/" + id + "/")).isEmpty();   // compensated
        } finally {
            jdbc.execute("DROP TRIGGER trg_fail_photo ON shipment_attachments");
        }
    }

    @Test void storageOutageDuringUploadIsReportedNotFaked() throws Exception {
        Session customer = customer();
        String id = draft(customer);
        doThrow(new StorageUnavailableException("Dịch vụ lưu trữ tệp tạm thời không khả dụng", null)).when(storage).put(anyString(), any(), anyLong(), anyString());
        upload(customer, id, png(2048), "hang.png").andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("select count(*) from shipment_attachments where shipment_id = ?::uuid", Long.class, id)).isZero();
    }

    @Test void largePhotosStreamWithinTheLimitsAndAccessIsChecked() throws Exception {
        Session customer = customer();
        String id = draft(customer);
        JsonNode photo = body(upload(customer, id, png(4_900_000), "lon.png").andExpect(status().isCreated()).andReturn()).get("data");
        assertThat(photo.get("fileSize").asLong()).isEqualTo(4_900_000);
        mvc.perform(get(photo.get("contentUrl").asText()).header("Authorization", bearer(customer))).andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsByteArray()).hasSize(4_900_000));
        Session stranger = customer();
        mvc.perform(get(photo.get("contentUrl").asText()).header("Authorization", bearer(stranger))).andExpect(status().isNotFound());
        upload(stranger, id, png(1024), "x.png").andExpect(status().isNotFound());
        upload(customer, id, "GIF89a not allowed".getBytes(), "x.gif").andExpect(status().isUnsupportedMediaType());
        for (int i = 1; i < 10; i++) uploaded(customer, id);
        upload(customer, id, png(1024), "11.png").andExpect(status().isConflict());   // 10 photos per order
    }

    @Test void orphanSweepDeletesOnlyObjectsNobodyRefersTo() throws Exception {
        Session customer = customer();
        String id = draft(customer);
        uploaded(customer, id);
        String orphan = "shipments/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".png";
        minioClient().putObject(PutObjectArgs.builder().bucket(BUCKET).object(orphan).stream(new ByteArrayInputStream(png(100)), 100, -1).contentType("image/png").build());
        String elsewhere = "exports/report.csv";   // outside the photo prefix: never touched
        minioClient().putObject(PutObjectArgs.builder().bucket(BUCKET).object(elsewhere).stream(new ByteArrayInputStream(new byte[] {1}), 1, -1).build());
        Thread.sleep(1500);   // past the (test) minimum age

        OrphanObjectSweeper.Report dry = sweeper.sweep(true);
        assertThat(dry.orphans()).isGreaterThanOrEqualTo(1);
        assertThat(objects(orphan)).hasSize(1);

        OrphanObjectSweeper.Report real;
        int guard = 0;
        do { real = sweeper.sweep(false); } while (real.queued() == 0 && ++guard < 3);
        assertThat(objects(orphan)).isEmpty();
        assertThat(objects("shipments/" + id + "/")).hasSize(1);   // the live photo stays
        assertThat(objects("exports/")).containsExactly(elsewhere);
    }

    @Test void geocodingWithoutAProviderKeepsManualEntryWorking() throws Exception {
        Session customer = customer();
        mvc.perform(get("/api/v1/geo/status").header("Authorization", bearer(customer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false)).andExpect(jsonPath("$.data.provider").value("none"));
        mvc.perform(get("/api/v1/geo/search?q=Số 1 Trần Thái Tông").header("Authorization", bearer(customer)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data.code").value("GEOCODING_DISABLED"));
        mvc.perform(get("/api/v1/geo/search?q=ab").header("Authorization", bearer(customer))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/geo/status")).andExpect(status().isUnauthorized());
        String id = draft(customer);   // still saves, coordinates stay blank
        assertThat(jdbc.queryForObject("select count(*) from shipment_stops where shipment_id = ?::uuid and latitude is null", Long.class, id)).isEqualTo(2);
    }
}
