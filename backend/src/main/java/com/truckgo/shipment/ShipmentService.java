package com.truckgo.shipment;

import com.truckgo.common.exception.ConflictException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.shipment.dto.ShipmentRequests.DraftRequest;
import com.truckgo.shipment.dto.ShipmentResponses.ShipmentDetail;
import com.truckgo.shipment.dto.ShipmentResponses.ShipmentSummary;
import com.truckgo.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Customer order lifecycle. Ownership always comes from the authenticated principal; an order that belongs to
 * someone else is reported as 404 so its id cannot be probed.
 */
@Service
public class ShipmentService {
    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);
    private static final int CODE_ATTEMPTS = 5;

    private final ShipmentRepository shipments;
    private final ShipmentAttachmentRepository attachments;
    private final ShipmentStatusHistoryRepository history;
    private final ShipmentDraftAssembler assembler;
    private final ShipmentMapper mapper;
    private final ShipmentCodeGenerator codes;
    private final ShipmentAttachmentCleanup cleanup;
    private final ShipmentProperties properties;
    private final Clock clock;

    public ShipmentService(ShipmentRepository shipments, ShipmentAttachmentRepository attachments, ShipmentStatusHistoryRepository history,
                           ShipmentDraftAssembler assembler, ShipmentMapper mapper, ShipmentCodeGenerator codes, ShipmentAttachmentCleanup cleanup,
                           ShipmentProperties properties, Clock clock) {
        this.shipments = shipments;
        this.attachments = attachments;
        this.history = history;
        this.assembler = assembler;
        this.mapper = mapper;
        this.codes = codes;
        this.cleanup = cleanup;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ShipmentDetail createDraft(UUID customerId, DraftRequest request) {
        Shipment shipment = new Shipment(uniqueCode(), customerId);
        assembler.apply(shipment, request);
        shipments.saveAndFlush(shipment);
        recordStatus(shipment, null, customerId, "Tạo đơn nháp");
        log.info("Shipment draft created shipmentId={} code={}", shipment.getId(), shipment.getShipmentCode());
        return detail(shipment, true);
    }

    /** Replaces stops, items and services; attachments are untouched. Requires the version the client last read. */
    @Transactional
    public ShipmentDetail updateDraft(UUID customerId, UUID shipmentId, DraftRequest request) {
        Shipment shipment = requireOwned(customerId, shipmentId);
        if (!shipment.getStatus().isEditable()) throw new ConflictException("Chỉ có thể chỉnh sửa đơn ở trạng thái nháp");
        if (request.version() == null) throw new IllegalArgumentException("Thiếu phiên bản dữ liệu (version)");
        if (request.version() != shipment.getVersion()) throw new ConflictException("Đơn đã được thay đổi ở nơi khác, vui lòng tải lại");
        shipment.clearDetails();
        // Remove the old rows first: Hibernate would otherwise insert the new stops/services before deleting the old
        // ones and trip the (shipment_id, sequence_no) and (shipment_id, service_code) unique keys.
        shipments.flush();
        assembler.apply(shipment, request);
        shipments.flush();
        return detail(shipment, true);
    }

    @Transactional
    public ShipmentDetail publish(UUID customerId, UUID shipmentId) {
        Shipment shipment = requireOwned(customerId, shipmentId);
        if (shipment.getStatus() != ShipmentStatus.DRAFT)
            throw new ConflictException(shipment.getStatus() == ShipmentStatus.PUBLISHED ? "Đơn đã được đăng" : "Chỉ có thể đăng đơn ở trạng thái nháp");
        assembler.validateForPublish(shipment, properties.minPickupLead());
        ShipmentStatus previous = shipment.getStatus();
        shipment.markPublished(clock.instant());
        shipments.flush();
        recordStatus(shipment, previous, customerId, "Khách hàng đăng đơn");
        log.info("Shipment published shipmentId={}", shipment.getId());
        return detail(shipment, true);
    }

    @Transactional
    public ShipmentDetail cancel(UUID customerId, UUID shipmentId, String reason) {
        Shipment shipment = requireOwned(customerId, shipmentId);
        if (!shipment.getStatus().isCancellableByCustomer())
            throw new ConflictException("Không thể hủy đơn ở trạng thái " + shipment.getStatus());
        ShipmentStatus previous = shipment.getStatus();
        shipment.markCancelled(clock.instant(), reason.trim());
        shipments.flush();
        recordStatus(shipment, previous, customerId, reason.trim());
        log.info("Shipment cancelled shipmentId={} from={}", shipment.getId(), previous);
        return detail(shipment, true);
    }

    /** Soft-deletes a draft and removes its photos from storage after the transaction commits. */
    @Transactional
    public void deleteDraft(UUID customerId, UUID shipmentId) {
        Shipment shipment = requireOwned(customerId, shipmentId);
        if (shipment.getStatus() != ShipmentStatus.DRAFT) throw new ConflictException("Chỉ có thể xóa đơn nháp; đơn đã đăng cần được hủy");
        List<ShipmentAttachment> files = attachments.findByShipmentIdOrderByCreatedAt(shipmentId);
        attachments.deleteAll(files);
        shipment.markDeleted(clock.instant());
        cleanup.deleteObjectsAfterCommit(files.stream().map(ShipmentAttachment::getFileKey).toList());
        log.info("Shipment draft deleted shipmentId={} attachments={}", shipmentId, files.size());
    }

    @Transactional(readOnly = true)
    public ShipmentDetail get(AuthenticatedUser viewer, UUID shipmentId) {
        Shipment shipment = requireViewable(viewer, shipmentId);
        return detail(shipment, shipment.isOwnedBy(viewer.id()));
    }

    @Transactional(readOnly = true)
    public PageResponse<ShipmentSummary> listMine(UUID customerId, ShipmentFilter filter) {
        return PageResponse.of(shipments.findAll(filter.specification(customerId, properties.businessZone()), filter.pageable()), s -> mapper.toSummary(s, true));
    }

    @Transactional(readOnly = true)
    public PageResponse<ShipmentSummary> listAll(ShipmentFilter filter) {
        return PageResponse.of(shipments.findAll(filter.specification(null, properties.businessZone()), filter.pageable()), s -> mapper.toSummary(s, false));
    }

    /** Owner or admin; anyone else gets 404. */
    public Shipment requireViewable(AuthenticatedUser viewer, UUID shipmentId) {
        Shipment shipment = shipments.findById(shipmentId).orElseThrow(ShipmentService::notFound);
        if (viewer.role() != UserRole.ADMIN && !shipment.isOwnedBy(viewer.id())) throw notFound();
        return shipment;
    }

    public Shipment requireOwned(UUID customerId, UUID shipmentId) {
        Shipment shipment = shipments.findById(shipmentId).orElseThrow(ShipmentService::notFound);
        if (!shipment.isOwnedBy(customerId)) throw notFound();
        return shipment;
    }

    private ShipmentDetail detail(Shipment shipment, boolean owner) {
        return mapper.toDetail(shipment, attachments.findByShipmentIdOrderByCreatedAt(shipment.getId()),
                history.findByShipmentIdOrderByCreatedAtAsc(shipment.getId()), owner);
    }

    private void recordStatus(Shipment shipment, ShipmentStatus previous, UUID actor, String reason) {
        history.save(new ShipmentStatusHistory(shipment.getId(), previous, shipment.getStatus(), actor, reason));
    }

    private String uniqueCode() {
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = codes.next();
            if (!shipments.existsByShipmentCode(code)) return code;
            log.warn("Shipment code collision, retrying");
        }
        throw new IllegalStateException("Could not allocate a unique shipment code");
    }

    private static ResourceNotFoundException notFound() { return new ResourceNotFoundException("Không tìm thấy đơn vận chuyển"); }
}
