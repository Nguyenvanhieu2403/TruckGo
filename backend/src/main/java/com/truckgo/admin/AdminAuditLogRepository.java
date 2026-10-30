package com.truckgo.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {
    List<AdminAuditLog> findByTargetIdOrderByCreatedAtAsc(UUID targetId);
}
