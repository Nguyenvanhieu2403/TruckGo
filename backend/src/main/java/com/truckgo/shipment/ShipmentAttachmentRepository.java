package com.truckgo.shipment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentAttachmentRepository extends JpaRepository<ShipmentAttachment, UUID> {
    List<ShipmentAttachment> findByShipmentIdOrderByCreatedAt(UUID shipmentId);
    Optional<ShipmentAttachment> findByIdAndShipmentId(UUID id, UUID shipmentId);
    long countByShipmentId(UUID shipmentId);
    long countByShipmentIdAndFileType(UUID shipmentId, AttachmentFileType fileType);
    long countByShipmentIdAndFileTypeNot(UUID shipmentId, AttachmentFileType fileType);
    List<ShipmentAttachment> findByShipmentIdAndFileTypeOrderByCreatedAt(UUID shipmentId, AttachmentFileType fileType);
    List<ShipmentAttachment> findByShipmentIdAndFileTypeNotOrderByCreatedAt(UUID shipmentId, AttachmentFileType fileType);
}
