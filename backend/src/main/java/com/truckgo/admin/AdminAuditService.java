package com.truckgo.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Persists admin actions in the caller's transaction, so the audit row and the change commit or roll back together. */
@Service
public class AdminAuditService {
    private static final Logger audit = LoggerFactory.getLogger("com.truckgo.audit");

    private final AdminAuditLogRepository logs;

    public AdminAuditService(AdminAuditLogRepository logs) { this.logs = logs; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, String action, String targetType, UUID targetId, String details) {
        logs.save(new AdminAuditLog(actorId, action, targetType, targetId, details));
        audit.info("admin action={} actorId={} targetType={} targetId={}", action, actorId, targetType, targetId);
    }
}
