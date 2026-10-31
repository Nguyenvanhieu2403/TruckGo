package com.truckgo.shipment;

import com.truckgo.common.exception.ConflictException;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.shipment.dto.ShipmentResponses.AttachmentResponse;
import com.truckgo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Goods photos. Only the order's owner may add or remove them, and only while the order is a draft. Files are
 * identified by content (magic bytes), stored under a random server-generated key, and served back through the API
 * after an ownership check.
 */
@Service
public class ShipmentAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(ShipmentAttachmentService.class);

    public record Content(String fileName, String contentType, long size, InputStream stream) { }

    private final ShipmentService shipments;
    private final ShipmentAttachmentRepository attachments;
    private final ShipmentAttachmentCleanup cleanup;
    private final ShipmentMapper mapper;
    private final StorageService storage;
    private final ShipmentProperties properties;

    public ShipmentAttachmentService(ShipmentService shipments, ShipmentAttachmentRepository attachments, ShipmentAttachmentCleanup cleanup,
                                     ShipmentMapper mapper, StorageService storage, ShipmentProperties properties) {
        this.shipments = shipments;
        this.attachments = attachments;
        this.cleanup = cleanup;
        this.mapper = mapper;
        this.storage = storage;
        this.properties = properties;
    }

    @Transactional
    public AttachmentResponse upload(UUID customerId, UUID shipmentId, MultipartFile file) {
        Shipment shipment = shipments.requireOwned(customerId, shipmentId);
        if (!shipment.getStatus().isEditable()) throw new ConflictException("Chỉ có thể thêm ảnh khi đơn ở trạng thái nháp");
        if (attachments.countByShipmentIdAndFileType(shipmentId, AttachmentFileType.GOODS_PHOTO) >= properties.maxAttachments())
            throw new ConflictException("Mỗi đơn tối đa " + properties.maxAttachments() + " ảnh");
        return mapper.attachment(shipment, store(shipment, file, AttachmentFileType.GOODS_PHOTO, customerId));
    }

    /**
     * Stores a proof photo taken by the assigned driver. The trip module checks the driver, the trip state and its own
     * per-trip limit before calling this; the file checks are the same as for goods photos.
     */
    @Transactional
    public ShipmentAttachment storeTripPhoto(Shipment shipment, MultipartFile file, AttachmentFileType type, UUID driverUserId) {
        if (!type.isTripPhoto()) throw new IllegalArgumentException("Loại ảnh không hợp lệ");
        return store(shipment, file, type, driverUserId);
    }

    /** Opens any photo of an order for a caller the trip module has already authorised. */
    @Transactional(readOnly = true)
    public Content openAuthorised(UUID shipmentId, UUID attachmentId) {
        ShipmentAttachment attachment = attachments.findByIdAndShipmentId(attachmentId, shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tệp đính kèm"));
        return new Content(attachment.getFileName(), attachment.getContentType(), attachment.getFileSize(), storage.get(attachment.getFileKey()));
    }

    private ShipmentAttachment store(Shipment shipment, MultipartFile file, AttachmentFileType fileType, UUID uploader) {
        UUID shipmentId = shipment.getId();
        if (file == null || file.isEmpty()) throw invalid("Tệp rỗng");
        if (file.getSize() > properties.maxAttachmentSize().toBytes())
            throw new FieldErrorException(HttpStatus.PAYLOAD_TOO_LARGE, "Tệp vượt quá dung lượng cho phép",
                    Map.of("file", "Ảnh tối đa " + properties.maxAttachmentSize().toMegabytes() + " MB"));
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException ex) { throw invalid("Không đọc được tệp tải lên"); }
        ImageTypeDetector.ImageType type = ImageTypeDetector.detect(bytes)
                .filter(t -> properties.allowedImageTypes().contains(t.mimeType()))
                .orElseThrow(() -> new FieldErrorException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Định dạng tệp không được hỗ trợ",
                        Map.of("file", "Chỉ chấp nhận ảnh JPEG, PNG hoặc WebP")));

        UUID attachmentId = UUID.randomUUID();
        String key = "shipments/" + shipmentId + "/" + attachmentId + "." + type.extension();
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, type.mimeType());
        try {
            ShipmentAttachment saved = attachments.saveAndFlush(new ShipmentAttachment(attachmentId, shipmentId, displayName(file.getOriginalFilename(), type.extension()),
                    key, type.mimeType(), bytes.length, fileType, uploader));
            log.info("Attachment uploaded shipmentId={} attachmentId={} type={} size={}", shipmentId, attachmentId, fileType, bytes.length);
            return saved;
        } catch (RuntimeException ex) {
            cleanup.deleteNow(List.of(key)); // compensate: never keep an object without metadata
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(AuthenticatedUser viewer, UUID shipmentId) {
        Shipment shipment = shipments.requireViewable(viewer, shipmentId);
        return attachments.findByShipmentIdOrderByCreatedAt(shipmentId).stream().map(a -> mapper.attachment(shipment, a)).toList();
    }

    @Transactional
    public void delete(UUID customerId, UUID shipmentId, UUID attachmentId) {
        Shipment shipment = shipments.requireOwned(customerId, shipmentId);
        if (!shipment.getStatus().isEditable()) throw new ConflictException("Chỉ có thể xóa ảnh khi đơn ở trạng thái nháp");
        ShipmentAttachment attachment = attachments.findByIdAndShipmentId(attachmentId, shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tệp đính kèm"));
        attachments.delete(attachment);
        cleanup.deleteObjectsAfterCommit(List.of(attachment.getFileKey()));
    }

    @Transactional(readOnly = true)
    public Content open(AuthenticatedUser viewer, UUID shipmentId, UUID attachmentId) {
        shipments.requireViewable(viewer, shipmentId);
        ShipmentAttachment attachment = attachments.findByIdAndShipmentId(attachmentId, shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tệp đính kèm"));
        return new Content(attachment.getFileName(), attachment.getContentType(), attachment.getFileSize(), storage.get(attachment.getFileKey()));
    }

    /** Display-only name: base name, no control characters or path separators, at most 255 chars. Never used as a path. */
    static String displayName(String original, String extension) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC).replaceAll("[\\p{Cntrl}\"<>|*?:]", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "anh-hang-hoa." + extension;
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static FieldErrorException invalid(String message) { return FieldErrorException.invalid("file", message); }
}
