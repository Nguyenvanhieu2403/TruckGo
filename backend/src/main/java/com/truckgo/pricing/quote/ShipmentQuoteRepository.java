package com.truckgo.pricing.quote;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentQuoteRepository extends JpaRepository<ShipmentQuote, UUID> {
    Optional<ShipmentQuote> findFirstByShipmentIdOrderByQuoteVersionDesc(UUID shipmentId);

    Optional<ShipmentQuote> findFirstByShipmentIdAndStatusIn(UUID shipmentId, Collection<QuoteStatus> statuses);

    List<ShipmentQuote> findByShipmentIdOrderByQuoteVersionDesc(UUID shipmentId);

    @Query("select coalesce(max(q.quoteVersion), 0) from ShipmentQuote q where q.shipmentId = :shipmentId")
    int maxVersion(@Param("shipmentId") UUID shipmentId);

    Page<ShipmentQuote> findByStatus(QuoteStatus status, Pageable pageable);

    /** Versions a customer created for one order since a moment (rate limiting; the database is the source of truth). */
    List<ShipmentQuote> findByShipmentIdAndCreatedByAndCreatedAtAfterOrderByCreatedAtAsc(UUID shipmentId, UUID createdBy, java.time.Instant after);

    List<ShipmentQuote> findByCreatedByAndCreatedAtAfterOrderByCreatedAtAsc(UUID createdBy, java.time.Instant after);
}
