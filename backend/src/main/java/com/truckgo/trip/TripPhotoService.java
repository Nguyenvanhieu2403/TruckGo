package com.truckgo.trip;

import com.truckgo.common.exception.BusinessRuleException;
import com.truckgo.shipment.AttachmentFileType;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentAttachment;
import com.truckgo.shipment.ShipmentAttachmentRepository;
import com.truckgo.shipment.ShipmentAttachmentService;
import com.truckgo.shipment.ShipmentAttachmentService.Content;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.trip.dto.TripDtos.PhotoView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Proof photos taken by the assigned driver, stored in MinIO through the Sprint 3 attachment pipeline (content-type
 * sniffing, size limit, random keys). Uploading returns an id that the pickup/deliver/incident request then refers to.
 */
@Service
public class TripPhotoService {
    private static final Map<AttachmentFileType, Set<ShipmentStatus>> ALLOWED = Map.of(
            AttachmentFileType.PICKUP_PHOTO, EnumSet.of(ShipmentStatus.ASSIGNED, ShipmentStatus.DRIVER_ARRIVED),
            AttachmentFileType.DELIVERY_PHOTO, EnumSet.of(ShipmentStatus.IN_TRANSIT),
            AttachmentFileType.INCIDENT_PHOTO, EnumSet.of(ShipmentStatus.ASSIGNED, ShipmentStatus.DRIVER_ARRIVED, ShipmentStatus.PICKED_UP,
                    ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED));

    private final TripTransitionService transitions;
    private final TripQueryService queries;
    private final ShipmentAttachmentService storage;
    private final ShipmentAttachmentRepository attachments;
    private final TripProperties properties;

    public TripPhotoService(TripTransitionService transitions, TripQueryService queries, ShipmentAttachmentService storage,
                            ShipmentAttachmentRepository attachments, TripProperties properties) {
        this.transitions = transitions;
        this.queries = queries;
        this.storage = storage;
        this.attachments = attachments;
        this.properties = properties;
    }

    @Transactional
    public PhotoView upload(UUID userId, UUID shipmentId, AttachmentFileType purpose, MultipartFile file) {
        if (purpose == null || !purpose.isTripPhoto()) throw new IllegalArgumentException("purpose phải là PICKUP_PHOTO, DELIVERY_PHOTO hoặc INCIDENT_PHOTO");
        Shipment shipment = transitions.lock(userId, shipmentId).shipment();
        if (!ALLOWED.get(purpose).contains(shipment.getStatus()))
            throw BusinessRuleException.conflict(TripErrors.INVALID_TRIP_STATUS, "Không thể tải loại ảnh này khi chuyến ở trạng thái " + shipment.getStatus());
        if (attachments.countByShipmentIdAndFileTypeNot(shipmentId, AttachmentFileType.GOODS_PHOTO) >= properties.maxTripPhotos())
            throw BusinessRuleException.conflict(TripErrors.INVALID_TRIP_STATUS, "Mỗi chuyến tối đa " + properties.maxTripPhotos() + " ảnh");
        ShipmentAttachment a = storage.storeTripPhoto(shipment, file, purpose, userId);
        return new PhotoView(a.getId(), a.getFileType(), a.getFileName(), a.getFileSize(), a.getCreatedAt(),
                "/api/v1/driver/trips/" + shipmentId + "/photos/" + a.getId() + "/content");
    }

    /** The assigned driver can open every photo of their trip (including the customer's goods photos). */
    @Transactional(readOnly = true)
    public Content open(UUID userId, UUID shipmentId, UUID attachmentId) {
        queries.requireDriverTrip(userId, shipmentId);
        return storage.openAuthorised(shipmentId, attachmentId);
    }
}
