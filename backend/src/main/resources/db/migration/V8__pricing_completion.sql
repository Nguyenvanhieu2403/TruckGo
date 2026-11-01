-- Sprint 4 completion: automatic quote expiry, re-pricing limits, per-stop services.

-- ---------------------------------------------------------------- quote expiry and re-pricing
-- The expiry job scans only live calculated quotes past their validity.
CREATE INDEX idx_shipment_quotes_expiry ON shipment_quotes (expires_at) WHERE status = 'CALCULATED';

-- What the quote was computed from (order data, chosen vehicle, price table version, route provider). Pressing
-- "Tính giá" again with an identical fingerprint returns the still-valid quote instead of a new version.
ALTER TABLE shipment_quotes ADD COLUMN input_fingerprint VARCHAR(64);

-- Rate limiting counts the versions a customer created recently (the database is the source of truth, not Redis).
CREATE INDEX idx_shipment_quotes_creator ON shipment_quotes (created_by, created_at DESC);
CREATE INDEX idx_shipment_quotes_shipment_created ON shipment_quotes (shipment_id, created_at DESC);

-- ---------------------------------------------------------------- per-stop services
-- Where a service may be chosen: the whole order (as before), individual stops, or both. stop_types limits which
-- stops (PICKUP / DELIVERY) a stop-level service applies to.
ALTER TABLE shipment_service_definitions
    ADD COLUMN applies_to VARCHAR(10) NOT NULL DEFAULT 'ORDER',
    ADD COLUMN stop_types VARCHAR(20),
    ADD CONSTRAINT chk_service_applies_to CHECK (applies_to IN ('ORDER', 'STOP', 'BOTH')),
    ADD CONSTRAINT chk_service_stop_types CHECK (stop_types IS NULL OR stop_types IN ('PICKUP', 'DELIVERY', 'ANY')),
    ADD CONSTRAINT chk_service_stop_scope CHECK (applies_to = 'ORDER' OR stop_types IS NOT NULL);

-- Loading at pickups and unloading at deliveries can now be ordered per stop. Existing order-level selections stay
-- valid and keep their old meaning (one charge for the order).
UPDATE shipment_service_definitions SET applies_to = 'BOTH', stop_types = 'PICKUP',
       description = 'Nhân sự bốc hàng tại điểm lấy (chọn cho cả đơn hoặc từng điểm lấy)' WHERE code = 'LOADING_SUPPORT';
UPDATE shipment_service_definitions SET applies_to = 'BOTH', stop_types = 'DELIVERY',
       description = 'Nhân sự dỡ hàng tại điểm giao (chọn cho cả đơn hoặc từng điểm giao)' WHERE code = 'UNLOADING_SUPPORT';

CREATE TABLE shipment_stop_services (
    id               UUID PRIMARY KEY,
    shipment_id      UUID         NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    shipment_stop_id UUID         NOT NULL REFERENCES shipment_stops (id) ON DELETE CASCADE,
    service_code     VARCHAR(40)  NOT NULL REFERENCES shipment_service_definitions (code),
    service_name     VARCHAR(100) NOT NULL,
    service_value    VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_stop_services UNIQUE (shipment_stop_id, service_code)
);
CREATE INDEX idx_stop_services_shipment ON shipment_stop_services (shipment_id);

-- Quote lines say whether they apply to the order or to particular stops (sequence numbers), as priced.
ALTER TABLE shipment_quote_items
    ADD COLUMN applies_to VARCHAR(10) NOT NULL DEFAULT 'ORDER',
    ADD COLUMN stop_sequences VARCHAR(200),
    ADD CONSTRAINT chk_quote_items_applies_to CHECK (applies_to IN ('ORDER', 'STOP')),
    ADD CONSTRAINT chk_quote_items_stops CHECK ((applies_to = 'STOP') = (stop_sequences IS NOT NULL));
