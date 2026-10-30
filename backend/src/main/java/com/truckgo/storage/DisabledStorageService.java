package com.truckgo.storage;

import java.io.InputStream;

/** Used when no storage is configured: every call fails loudly instead of pretending the upload worked. */
public class DisabledStorageService implements StorageService {
    private static final String MESSAGE = "Chức năng tải tệp chưa được cấu hình trên máy chủ";

    @Override public void put(String key, InputStream data, long size, String contentType) { throw new StorageUnavailableException(MESSAGE, null); }
    @Override public InputStream get(String key) { throw new StorageUnavailableException(MESSAGE, null); }
    @Override public void delete(String key) { throw new StorageUnavailableException(MESSAGE, null); }
}
