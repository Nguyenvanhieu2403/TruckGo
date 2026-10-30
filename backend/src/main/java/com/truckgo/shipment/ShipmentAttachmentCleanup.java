package com.truckgo.shipment;

import com.truckgo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Deletes stored objects only after the database change that orphaned them has committed, so a rollback never
 * leaves metadata pointing at a missing file. A failed delete is logged with the key for later cleanup.
 */
@Component
public class ShipmentAttachmentCleanup {
    private static final Logger log = LoggerFactory.getLogger(ShipmentAttachmentCleanup.class);

    private final StorageService storage;

    public ShipmentAttachmentCleanup(StorageService storage) { this.storage = storage; }

    public void deleteObjectsAfterCommit(List<String> keys) {
        if (keys.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { deleteNow(keys); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { deleteNow(keys); }
        });
    }

    public void deleteNow(List<String> keys) {
        for (String key : keys) {
            try { storage.delete(key); }
            catch (RuntimeException ex) { log.error("Orphaned storage object needs cleanup key={}", key); }
        }
    }
}
