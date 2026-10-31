package com.truckgo.trip;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/** Links a proof photo to the pickup or delivery milestone. */
@Entity
@Immutable
@Table(name = "shipment_milestone_attachments")
public class MilestoneAttachment {
    public enum Milestone { PICKUP, DELIVERY }

    @Id @Column(name = "attachment_id") private UUID attachmentId;
    @Column(name = "shipment_id", nullable = false) private UUID shipmentId;
    @Column(nullable = false) private String milestone;

    protected MilestoneAttachment() { }

    public MilestoneAttachment(UUID shipmentId, Milestone milestone, UUID attachmentId) {
        this.shipmentId = shipmentId;
        this.milestone = milestone.name();
        this.attachmentId = attachmentId;
    }

    public UUID getAttachmentId() { return attachmentId; }
    public UUID getShipmentId() { return shipmentId; }
    public Milestone getMilestone() { return Milestone.valueOf(milestone); }
}
