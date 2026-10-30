package com.truckgo.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * MinIO / S3 adapter. The bucket is created private (no anonymous policy) and objects are only ever streamed through
 * the API after an ownership check, so neither the endpoint nor credentials reach the client.
 */
public class MinioStorageService implements StorageService {
    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);
    private static final String UNAVAILABLE = "Dịch vụ lưu trữ tệp tạm thời không khả dụng";

    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketReady;

    public MinioStorageService(StorageProperties properties) {
        MinioClient.Builder builder = MinioClient.builder().endpoint(properties.endpoint()).credentials(properties.accessKey(), properties.secretKey());
        if (properties.region() != null && !properties.region().isBlank()) builder.region(properties.region());
        this.client = builder.build();
        this.bucket = properties.bucket();
    }

    @Override public void put(String key, InputStream data, long size, String contentType) {
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(data, size, -1).contentType(contentType).build());
        } catch (StorageUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable("put", ex);
        }
    }

    @Override public InputStream get(String key) {
        try { return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception ex) { throw unavailable("get", ex); }
    }

    @Override public void delete(String key) {
        try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception ex) { throw unavailable("delete", ex); }
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) return;
        synchronized (this) {
            if (bucketReady) return;
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created private storage bucket {}", bucket);
            }
            bucketReady = true;
        }
    }

    private StorageUnavailableException unavailable(String operation, Exception ex) {
        log.error("Object storage {} failed: {}", operation, ex.getClass().getSimpleName());
        return new StorageUnavailableException(UNAVAILABLE, ex);
    }
}
