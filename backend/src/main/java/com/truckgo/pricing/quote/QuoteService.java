package com.truckgo.pricing.quote;

import com.truckgo.admin.AdminAuditService;
import com.truckgo.common.exception.ConflictException;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import com.truckgo.pricing.dto.QuoteDtos.AdminQuoteResponse;
import com.truckgo.pricing.dto.QuoteDtos.QuoteResponse;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentDetailsChangedEvent;
import com.truckgo.shipment.ShipmentPriceProvider;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Quote lifecycle. Creating a version locks the shipment row, so concurrent requests are serialised and versions
 * stay consecutive with a single current quote. Accepted quotes are never re-priced; a changed order supersedes them.
 */
@Service
public class QuoteService implements ShipmentPriceProvider {
    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    /** Signals an expired quote; the EXPIRED status is committed even though the request fails. */
    static class QuoteExpiredException extends ConflictException {
        QuoteExpiredException() { super("Báo giá đã hết hạn, vui lòng tính lại giá"); }
    }

    private final ShipmentQuoteRepository quotes;
    private final ShipmentRepository shipments;
    private final QuoteFactory factory;
    private final QuoteMapper mapper;
    private final AdminAuditService audit;
    private final Clock clock;

    public QuoteService(ShipmentQuoteRepository quotes, ShipmentRepository shipments, QuoteFactory factory, QuoteMapper mapper, AdminAuditService audit, Clock clock) {
        this.quotes = quotes;
        this.shipments = shipments;
        this.factory = factory;
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ customer

    /** Prices the order now and stores it as a new version (the previous current version is superseded). */
    @Transactional
    public QuoteResponse preview(UUID customerId, UUID shipmentId) {
        Shipment shipment = lockOwned(customerId, shipmentId);
        requireQuotable(shipment);
        Optional<ShipmentQuote> current = current(shipmentId);
        if (current.filter(q -> q.getStatus() == QuoteStatus.ACCEPTED).isPresent())
            throw new ConflictException("Báo giá đã được xác nhận; hãy sửa đơn nháp nếu cần báo giá mới");
        factory.validateShipment(shipment);
        ShipmentQuote quote = newVersion(shipment, current, customerId, QuoteFactory.DistanceInput.fromProvider(), null, null, null);
        return mapper.toResponse(quote, shipment, true);
    }

    @Transactional(noRollbackFor = QuoteExpiredException.class)
    public Optional<QuoteResponse> getCurrent(AuthenticatedUser viewer, UUID shipmentId) {
        Shipment shipment = viewable(viewer, shipmentId);
        return quotes.findFirstByShipmentIdOrderByQuoteVersionDesc(shipmentId).map(q -> {
            expireIfDue(q);
            return mapper.toResponse(q, shipment, shipment.isOwnedBy(viewer.id()));
        });
    }

    @Transactional(readOnly = true)
    public List<QuoteResponse> history(AuthenticatedUser viewer, UUID shipmentId) {
        Shipment shipment = viewable(viewer, shipmentId);
        return quotes.findByShipmentIdOrderByQuoteVersionDesc(shipmentId).stream().map(q -> mapper.toResponse(q, shipment, false)).toList();
    }

    @Transactional(noRollbackFor = QuoteExpiredException.class)
    public QuoteResponse accept(UUID customerId, UUID shipmentId) {
        Shipment shipment = lockOwned(customerId, shipmentId);
        requireQuotable(shipment);
        ShipmentQuote quote = current(shipmentId).orElseThrow(() -> new ConflictException("Đơn chưa có báo giá để xác nhận"));
        if (expireIfDue(quote)) throw new QuoteExpiredException();
        quote.markAccepted(clock.instant());
        shipment.setEstimatedPrice(quote.getTotalPrice());
        quotes.flush();
        log.info("Quote accepted quote={} shipment={} total={}", quote.getQuoteCode(), shipmentId, quote.getTotalPrice());
        return mapper.toResponse(quote, shipment, true);
    }

    @Transactional(noRollbackFor = QuoteExpiredException.class)
    public QuoteResponse reject(UUID customerId, UUID shipmentId, String reason) {
        Shipment shipment = lockOwned(customerId, shipmentId);
        ShipmentQuote quote = current(shipmentId).orElseThrow(() -> new ConflictException("Đơn chưa có báo giá để từ chối"));
        if (expireIfDue(quote)) throw new QuoteExpiredException();
        quote.markRejected(clock.instant(), reason == null || reason.isBlank() ? null : reason.trim());
        quotes.flush();
        return mapper.toResponse(quote, shipment, true);
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    public PageResponse<AdminQuoteResponse> pendingReview(Pageable pageable) {
        return PageResponse.of(quotes.findByStatus(QuoteStatus.AWAITING_MANUAL_REVIEW, pageable),
                q -> mapper.toAdminResponse(q, shipments.findById(q.getShipmentId()).orElse(null)));
    }

    @Transactional(readOnly = true)
    public AdminQuoteResponse adminGet(UUID quoteId) {
        ShipmentQuote quote = quotes.findById(quoteId).orElseThrow(QuoteService::quoteNotFound);
        return mapper.toAdminResponse(quote, shipments.findById(quote.getShipmentId()).orElse(null));
    }

    /**
     * Creates a new version with an admin-verified distance and/or a signed adjustment amount. The reason, admin and
     * time are stored on the quote and in the admin audit log.
     */
    @Transactional
    public AdminQuoteResponse manualAdjustment(UUID adminId, UUID quoteId, BigDecimal distanceKm, BigDecimal adjustmentAmount, String reason) {
        ShipmentQuote base = quotes.findById(quoteId).orElseThrow(QuoteService::quoteNotFound);
        Shipment shipment = shipments.findByIdForUpdate(base.getShipmentId()).orElseThrow(QuoteService::quoteNotFound);
        ShipmentQuote current = requireAdjustable(base);
        requireQuotable(shipment);
        BigDecimal km = distanceKm != null ? distanceKm : current.getDistanceKm();
        if (km == null) throw FieldErrorException.invalid("distanceKm", "Nhập quãng đường đã xác minh cho báo giá này");
        factory.validateShipment(shipment);
        ShipmentQuote quote = newVersion(shipment, Optional.of(current), adminId, QuoteFactory.DistanceInput.manual(km), adjustmentAmount, adminId, reason.trim());
        audit.record(adminId, "QUOTE_MANUAL_ADJUSTMENT", "SHIPMENT_QUOTE", quote.getId(),
                "from " + current.getQuoteCode() + "; distanceKm=" + km.toPlainString() + "; adjustment=" + (adjustmentAmount == null ? "0" : adjustmentAmount.toPlainString()) + "; " + reason.trim());
        return mapper.toAdminResponse(quote, shipment);
    }

    /** Re-prices with the route provider; keeps a previously verified manual distance when the provider still has none. */
    @Transactional
    public AdminQuoteResponse recalculate(UUID adminId, UUID quoteId) {
        ShipmentQuote base = quotes.findById(quoteId).orElseThrow(QuoteService::quoteNotFound);
        Shipment shipment = shipments.findByIdForUpdate(base.getShipmentId()).orElseThrow(QuoteService::quoteNotFound);
        ShipmentQuote current = requireAdjustable(base);
        requireQuotable(shipment);
        factory.validateShipment(shipment);
        QuoteFactory.DistanceInput distance = "MANUAL".equals(current.getDistanceSource()) && current.getDistanceKm() != null
                ? QuoteFactory.DistanceInput.manual(current.getDistanceKm()) : QuoteFactory.DistanceInput.fromProvider();
        ShipmentQuote quote = newVersion(shipment, Optional.of(current), adminId, distance, null, null, null);
        audit.record(adminId, "QUOTE_RECALCULATED", "SHIPMENT_QUOTE", quote.getId(), "from " + current.getQuoteCode() + " -> " + quote.getStatus());
        return mapper.toAdminResponse(quote, shipment);
    }

    // ------------------------------------------------------------------ integration with shipments

    /** A changed draft invalidates its price, including an accepted one (the accepted amounts stay untouched). */
    @EventListener
    public void onShipmentChanged(ShipmentDetailsChangedEvent event) {
        current(event.shipmentId()).ifPresent(q -> {
            q.markSuperseded(clock.instant());
            quotes.flush();
            log.info("Quote superseded by order change quote={}", q.getQuoteCode());
        });
    }

    @Override
    public Optional<CurrentPrice> currentPrice(UUID shipmentId) {
        return current(shipmentId).map(q -> switch (q.getStatus()) {
            case CALCULATED -> q.isExpiredAt(clock.instant()) ? new CurrentPrice(State.NOT_QUOTED, null) : new CurrentPrice(State.QUOTED, q.getTotalPrice());
            case ACCEPTED -> new CurrentPrice(State.ACCEPTED, q.getTotalPrice());
            case AWAITING_MANUAL_REVIEW, PENDING -> new CurrentPrice(State.AWAITING_REVIEW, null);
            default -> new CurrentPrice(State.NOT_QUOTED, null);
        });
    }

    // ------------------------------------------------------------------ helpers

    private ShipmentQuote newVersion(Shipment shipment, Optional<ShipmentQuote> current, UUID actor, QuoteFactory.DistanceInput distance,
                                     BigDecimal adjustment, UUID adjustedBy, String reason) {
        current.ifPresent(q -> q.markSuperseded(clock.instant()));
        quotes.flush(); // release the "one current quote" unique slot before inserting the new version
        int version = quotes.maxVersion(shipment.getId()) + 1;
        // The id is assigned up front, so Spring Data merges: always continue with the managed instance it returns.
        ShipmentQuote quote = quotes.saveAndFlush(factory.build(shipment, version, actor, distance, adjustment, adjustedBy, reason));
        log.info("Quote created quote={} status={} total={}", quote.getQuoteCode(), quote.getStatus(), quote.getTotalPrice());
        return quote;
    }

    private Optional<ShipmentQuote> current(UUID shipmentId) { return quotes.findFirstByShipmentIdAndStatusIn(shipmentId, QuoteStatus.CURRENT); }

    /** Marks a CALCULATED quote past its expiry as EXPIRED; returns true when it did. */
    private boolean expireIfDue(ShipmentQuote quote) {
        if (!quote.isExpiredAt(clock.instant())) return false;
        quote.markExpired();
        quotes.flush();
        return true;
    }

    private ShipmentQuote requireAdjustable(ShipmentQuote quote) {
        ShipmentQuote current = current(quote.getShipmentId()).orElseThrow(() -> new ConflictException("Đơn không có báo giá hiện hành"));
        if (!current.getId().equals(quote.getId())) throw new ConflictException("Báo giá này đã được thay thế bởi phiên bản " + current.getQuoteCode());
        if (current.getStatus() == QuoteStatus.ACCEPTED) throw new ConflictException("Không thể điều chỉnh báo giá đã được khách hàng xác nhận");
        return current;
    }

    private static void requireQuotable(Shipment shipment) {
        if (shipment.getStatus() != ShipmentStatus.DRAFT && shipment.getStatus() != ShipmentStatus.PUBLISHED)
            throw new ConflictException("Không thể báo giá cho đơn ở trạng thái " + shipment.getStatus());
    }

    private Shipment lockOwned(UUID customerId, UUID shipmentId) {
        Shipment shipment = shipments.findByIdForUpdate(shipmentId).orElseThrow(QuoteService::shipmentNotFound);
        if (!shipment.isOwnedBy(customerId)) throw shipmentNotFound();
        return shipment;
    }

    private Shipment viewable(AuthenticatedUser viewer, UUID shipmentId) {
        Shipment shipment = shipments.findById(shipmentId).orElseThrow(QuoteService::shipmentNotFound);
        if (viewer.role() != UserRole.ADMIN && !shipment.isOwnedBy(viewer.id())) throw shipmentNotFound();
        return shipment;
    }

    private static ResourceNotFoundException shipmentNotFound() { return new ResourceNotFoundException("Không tìm thấy đơn vận chuyển"); }

    private static ResourceNotFoundException quoteNotFound() { return new ResourceNotFoundException("Không tìm thấy báo giá"); }
}
