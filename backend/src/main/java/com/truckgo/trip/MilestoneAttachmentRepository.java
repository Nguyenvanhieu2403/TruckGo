package com.truckgo.trip;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface MilestoneAttachmentRepository extends Repository<MilestoneAttachment, UUID> {
    MilestoneAttachment save(MilestoneAttachment link);
    List<MilestoneAttachment> findByShipmentId(UUID shipmentId);
    boolean existsByAttachmentId(UUID attachmentId);
}
