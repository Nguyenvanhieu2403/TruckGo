package com.truckgo.shipment;

import com.truckgo.common.exception.ConflictException;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.shipment.dto.ShipmentResponses.AttachmentResponse;
import com.truckgo.storage.StorageDeletionQueue;
import com.truckgo.storage.StorageDeletionTask;
import com.truckgo.storage.StorageService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Order photos. Goods photos: only the order's owner may add or remove them, and only while the order is a draft. Files are
 * identified by content (magic bytes), stored under a random server-generated key, and served back through the API
 * after an ownership check.
 */
@Service
public class ShipmentAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(ShipmentAttachmentService.class);

    public record Content(String fileName, String contentType, long size, InputStream stream) { }

    /** Enough for every supported signature (WebP needs 12). */
    private static final int HEADER_BYTES = 16;

    private final ShipmentService shipments;
    private final ShipmentAttachmentRepository attachments;
    private final StorageDeletionQueue deletions;
    private final ShipmentMapper mapper;
    private final StorageService storage;
    private final ShipmentProperties properties;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    public ShipmentAttachmentService(ShipmentService shipments, ShipmentAttachmentRepository attachments, StorageDeletionQueue deletions,
                                     ShipmentMapper mapper, StorageService storage, ShipmentProperties properties, PlatformTransactionManager transactions) {
        this.shipments = shipments;
        this.attachments = attachments;
        this.deletions = deletions;
        this.mapper = mapper;
        this.storage = storage;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
        this.readOnlyTx = new TransactionTemplate(transactions);
        this.readOnlyTx.setReadOnly(true);
    }

    /** Goods photo on the customer's own draft (at most maxAttachments per order). */
    public AttachmentResponse upload(UUID customerId, UUID shipmentId, MultipartFile file) {
        ShipmentAttachment saved = store(shipmentId, file, AttachmentFileType.GOODS_PHOTO, customerId, shipment -> {
            if (!shipment.isOwnedBy(customerId)) throw new ResourceNotFoundException("Không tìm thấy đơn vận chuyển");
            if (!shipment.getStatus().isEditable()) throw new ConflictException("Chỉ có thể thêm ảnh khi đơn ở trạng thái nháp");
            if (attachments.countByShipmentIdAndFileType(shipmentId, AttachmentFileType.GOODS_PHOTO) >= properties.maxAttachments())
                throw new ConflictException("Mỗi đơn tối đa " + properties.maxAttachments() + " ảnh");
        });
        return mapper.attachment(shipments.requireOwned(customerId, shipmentId), saved);
    }

    /**
     * Stores a photo in three steps so no database transaction or row lock is held while bytes go to storage:
     * <ol>
     *   <li>validate the file (size, magic bytes read from the stream head) and run {@code guard} read-only;</li>
     *   <li>stream the file to storage under a fresh random key;</li>
     *   <li>lock the order, run {@code guard} again (state may have changed meanwhile) and insert the metadata.</li>
     * </ol>
     * If step 3 fails the object is deleted again (or queued for deletion), so storage never keeps a file without
     * metadata. The guard decides who may upload what (owner and draft for goods photos, assigned driver for trip photos).
     */
    public ShipmentAttachment store(UUID shipmentId, MultipartFile file, AttachmentFileType fileType, UUID uploader, Consumer<Shipment> guard) {
        if (file == null || file.isEmpty()) throw invalid("Tệp rỗng");
        if (file.getSize() > properties.maxAttachmentSize().toBytes())
            throw new FieldErrorException(HttpStatus.PAYLOAD_TOO_LARGE, "Tệp vượt quá dung lượng cho phép",
                    Map.of("file", "Ảnh tối đa " + properties.maxAttachmentSize().toMegabytes() + " MB"));
        readOnlyTx.executeWithoutResult(s -> guard.accept(shipments.requireExisting(shipmentId)));

        UUID attachmentId = UUID.randomUUID();
        ImageTypeDetector.ImageType type;
        String key;
        try (InputStream in = new BufferedInputStream(file.getInputStream(), HEADER_BYTES * 2)) {
            in.mark(HEADER_BYTES);
            byte[] head = in.readNBytes(HEADER_BYTES);
            in.reset();
            type = ImageTypeDetector.detect(head).filter(t -> properties.allowedImageTypes().contains(t.mimeType()))
                    .orElseThrow(() -> new FieldErrorException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Định dạng tệp không được hỗ trợ",
                            Map.of("file", "Chỉ chấp nhận ảnh JPEG, PNG hoặc WebP")));
            key = "shipments/" + shipmentId + "/" + attachmentId + "." + type.extension();
            storage.put(key, in, file.getSize(), type.mimeType());   // streamed, never fully in memory
        } catch (IOException ex) {
            throw invalid("Không đọc được tệp tải lên");
        }

        try {
            ShipmentAttachment saved = tx.execute(s -> {
                guard.accept(shipments.lockExisting(shipmentId));
                return attachments.saveAndFlush(new ShipmentAttachment(attachmentId, shipmentId, displayName(file.getOriginalFilename(), type.extension()),
                        key, type.mimeType(), file.getSize(), fileType, uploader));
            });
            log.info("Attachment uploaded shipmentId={} attachmentId={} type={} size={}", shipmentId, attachmentId, fileType, file.getSize());
            return saved;
        } catch (RuntimeException ex) {
            deletions.compensate(key, shipmentId);   // never keep an object without metadata
            throw ex;
        }
    }

    /** Opens any photo of an order for a caller the trip module has already authorised. */
    @Transactional(readOnly = true)
    public Content openAuthorised(UUID shipmentId, UUID attachmentId) {
        ShipmentAttachment attachment = attachments.findByIdAndShipmentId(attachmentId, shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tệp đính kèm"));
        return new Content(attachment.getFileName(), attachment.getContentType(), attachment.getFileSize(), storage.get(attachment.getFileKey()));
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
        deletions.enqueue(List.of(attachment.getFileKey()), StorageDeletionTask.Reason.ATTACHMENT_DELETED, shipmentId);
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
