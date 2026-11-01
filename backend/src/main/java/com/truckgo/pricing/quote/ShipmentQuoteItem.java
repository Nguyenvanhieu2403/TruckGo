package com.truckgo.pricing.quote;

import com.truckgo.pricing.engine.PriceComponent;
import com.truckgo.pricing.engine.QuoteLine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "shipment_quote_items")
public class ShipmentQuoteItem {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "quote_id") private ShipmentQuote quote;
    @Column(name = "line_no", nullable = false) private int lineNo;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PriceComponent component;
    @Column(name = "item_code", nullable = false) private String itemCode;
    @Column(name = "item_name", nullable = false) private String itemName;
    private String description;
    @Column(nullable = false) private BigDecimal quantity;
    @Column(name = "unit_price", nullable = false) private BigDecimal unitPrice;
    @Column(nullable = false) private BigDecimal amount;
    /** ORDER or STOP; stop sequence numbers ("1,3") for per-stop services, as priced. */
    @Column(name = "applies_to", nullable = false) private String appliesTo = "ORDER";
    @Column(name = "stop_sequences") private String stopSequences;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ShipmentQuoteItem() { }

    ShipmentQuoteItem(ShipmentQuote quote, int lineNo, QuoteLine line, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.quote = quote;
        this.lineNo = lineNo;
        this.component = line.component();
        this.itemCode = line.code().length() > 60 ? line.code().substring(0, 60) : line.code();
        this.itemName = line.name().length() > 150 ? line.name().substring(0, 150) : line.name();
        this.description = line.description();
        this.quantity = line.quantity().setScale(3, java.math.RoundingMode.HALF_UP);
        this.unitPrice = line.unitPrice().setScale(2, java.math.RoundingMode.HALF_UP);
        this.amount = line.amount();
        this.appliesTo = line.appliesTo() == null ? "ORDER" : line.appliesTo();
        this.stopSequences = line.stopSequences() == null ? null : line.stopSequences().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        this.createdAt = createdAt;
    }

    public int getLineNo() { return lineNo; }
    public PriceComponent getComponent() { return component; }
    public String getItemCode() { return itemCode; }
    public String getItemName() { return itemName; }
    public String getDescription() { return description; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getAmount() { return amount; }
    public String getAppliesTo() { return appliesTo; }
    public java.util.List<Integer> getStopSequences() {
        return stopSequences == null ? java.util.List.of() : java.util.Arrays.stream(stopSequences.split(",")).map(Integer::valueOf).toList();
    }
}
