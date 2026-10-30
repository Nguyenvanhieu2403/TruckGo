package com.truckgo.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

@Configuration
public class StorageConfig {
    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    StorageService storageService(StorageProperties properties) {
        String provider = properties.provider() == null ? "none" : properties.provider().trim().toLowerCase(Locale.ROOT);
        if ("minio".equals(provider)) {
            if (isBlank(properties.endpoint()) || isBlank(properties.accessKey()) || isBlank(properties.secretKey()) || isBlank(properties.bucket()))
                throw new IllegalStateException("app.storage provider=minio requires endpoint, access key, secret key and bucket");
            return new MinioStorageService(properties);
        }
        if (!"none".equals(provider)) throw new IllegalStateException("Unknown app.storage.provider: " + provider);
        log.warn("Object storage is not configured (app.storage.provider=none); shipment photo uploads will be rejected.");
        return new DisabledStorageService();
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
}
