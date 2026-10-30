package com.truckgo.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** provider: "minio" (any S3-compatible endpoint) or "none" (uploads are rejected with 503). */
@ConfigurationProperties("app.storage")
public record StorageProperties(String provider, String endpoint, String accessKey, String secretKey, String bucket, String region) { }
