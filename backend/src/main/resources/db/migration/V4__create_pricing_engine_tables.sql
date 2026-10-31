-- Sprint 4: pricing engine. Price tables are configured by admins (no prices are seeded here); every quote stores a
-- snapshot of the values it was computed with so later price changes never alter an existing quote.
-- Money is VND in NUMERIC; computed amounts are whole đồng.

-- Needed for the "one active config per vehicle type and period" exclusion constraint (trusted extension in PG 13+).
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------- price tables
CREATE TABLE pricing_configs (
    id                   UUID PRIMARY KEY,
    name                 VARCHAR(150)  NOT NULL,
    vehicle_type_code    VARCHAR(40)   NOT NULL REFERENCES vehicle_types (code),
    base_price           NUMERIC(14,2) NOT NULL,
    price_per_km         NUMERIC(14,2) NOT NULL,
    included_distance_km NUMERIC(10,2) NOT NULL DEFAULT 0,
    included_weight_kg   NUMERIC(12,2) NOT NULL DEFAULT 0,
    price_per_extra_kg   NUMERIC(14,2) NOT NULL DEFAULT 0,
    included_volume_m3   NUMERIC(14,6) NOT NULL DEFAULT 0,
    price_per_extra_m3   NUMERIC(14,2) NOT NULL DEFAULT 0,
    additional_stop_price NUMERIC(14,2) NOT NULL DEFAULT 0,
    minimum_price        NUMERIC(14,2) NOT NULL DEFAULT 0,
    currency             CHAR(3)       NOT NULL DEFAULT 'VND',
    effective_from       TIMESTAMPTZ   NOT NULL,
    effective_to         TIMESTAMPTZ,
    is_active            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by           UUID          NOT NULL REFERENCES users (id),
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_pricing_configs_amounts CHECK (base_price >= 0 AND price_per_km >= 0 AND included_distance_km >= 0 AND included_weight_kg >= 0
        AND price_per_extra_kg >= 0 AND included_volume_m3 >= 0 AND price_per_extra_m3 >= 0 AND additional_stop_price >= 0 AND minimum_price >= 0),
    CONSTRAINT chk_pricing_configs_currency CHECK (currency = 'VND'),
    CONSTRAINT chk_pricing_configs_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    -- At most one active config per vehicle type for any instant.
    CONSTRAINT ex_pricing_configs_active_period EXCLUDE USING gist (
        vehicle_type_code WITH =, tstzrange(effective_from, effective_to, '[)') WITH &&) WHERE (is_active)
);
CREATE INDEX idx_pricing_configs_vehicle ON pricing_configs (vehicle_type_code, is_active, effective_from);

-- pricing_type: FIXED (amount), PERCENTAGE (percent of base + distance), PER_UNIT (amount × the service's numeric value,
-- or × total parcels for non-numeric services), PER_STOP (amount × number of stops), FREE.
-- service_option_value targets one option of an OPTION service (e.g. LOADING_SUPPORT=TEAM); NULL applies to any value.
CREATE TABLE pricing_service_fees (
    id                   UUID PRIMARY KEY,
    pricing_config_id    UUID          NOT NULL REFERENCES pricing_configs (id),
    service_code         VARCHAR(40)   NOT NULL REFERENCES shipment_service_definitions (code),
    service_option_value VARCHAR(40),
    pricing_type         VARCHAR(20)   NOT NULL,
    amount               NUMERIC(14,2) NOT NULL DEFAULT 0,
    percentage           NUMERIC(7,4)  NOT NULL DEFAULT 0,
    is_active            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_pricing_service_fees_type CHECK (pricing_type IN ('FIXED', 'PERCENTAGE', 'PER_UNIT', 'PER_STOP', 'FREE')),
    CONSTRAINT chk_pricing_service_fees_values CHECK (amount >= 0 AND percentage >= 0 AND percentage <= 1000),
    CONSTRAINT fk_pricing_service_fees_option FOREIGN KEY (service_code, service_option_value) REFERENCES shipment_service_options (service_code, value)
);
CREATE UNIQUE INDEX uq_pricing_service_fees_active ON pricing_service_fees (pricing_config_id, service_code, COALESCE(service_option_value, '')) WHERE is_active;
CREATE INDEX idx_pricing_service_fees_config ON pricing_service_fees (pricing_config_id);

-- Time-window surcharges, evaluated against the pickup time in the business time zone.
-- days_of_week: comma separated MON..SUN, NULL = every day. A window may cross midnight (start_time > end_time).
CREATE TABLE pricing_time_rules (
    id                UUID PRIMARY KEY,
    pricing_config_id UUID          NOT NULL REFERENCES pricing_configs (id) ON DELETE CASCADE,
    name              VARCHAR(100)  NOT NULL,
    days_of_week      VARCHAR(30),
    start_time        TIME          NOT NULL,
    end_time          TIME          NOT NULL,
    fee_type          VARCHAR(20)   NOT NULL,
    amount            NUMERIC(14,2) NOT NULL DEFAULT 0,
    percentage        NUMERIC(7,4)  NOT NULL DEFAULT 0,
    CONSTRAINT chk_pricing_time_rules_type CHECK (fee_type IN ('FIXED', 'PERCENTAGE')),
    CONSTRAINT chk_pricing_time_rules_values CHECK (amount >= 0 AND percentage >= 0 AND percentage <= 1000),
    CONSTRAINT chk_pricing_time_rules_window CHECK (start_time <> end_time)
);

-- Other admin-defined surcharges applied to every quote of the config (fuel, tolls, platform fee…).
CREATE TABLE pricing_extra_fees (
    id                UUID PRIMARY KEY,
    pricing_config_id UUID          NOT NULL REFERENCES pricing_configs (id) ON DELETE CASCADE,
    code              VARCHAR(40)   NOT NULL,
    name              VARCHAR(100)  NOT NULL,
    fee_type          VARCHAR(20)   NOT NULL,
    amount            NUMERIC(14,2) NOT NULL DEFAULT 0,
    percentage        NUMERIC(7,4)  NOT NULL DEFAULT 0,
    CONSTRAINT uq_pricing_extra_fees_code UNIQUE (pricing_config_id, code),
    CONSTRAINT chk_pricing_extra_fees_type CHECK (fee_type IN ('FIXED', 'PERCENTAGE')),
    CONSTRAINT chk_pricing_extra_fees_values CHECK (amount >= 0 AND percentage >= 0 AND percentage <= 1000)
);

-- Append-only audit of price table changes (JSON before/after).
CREATE TABLE pricing_config_histories (
    id                UUID PRIMARY KEY,
    pricing_config_id UUID        NOT NULL REFERENCES pricing_configs (id),
    action            VARCHAR(40) NOT NULL,
    old_value         JSONB,
    new_value         JSONB,
    changed_by        UUID        NOT NULL REFERENCES users (id),
    changed_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_pricing_config_histories_config ON pricing_config_histories (pricing_config_id, changed_at);

-- ---------------------------------------------------------------- quotes
CREATE TABLE shipment_quotes (
    id                        UUID PRIMARY KEY,
    shipment_id               UUID          NOT NULL REFERENCES shipments (id),
    quote_code                VARCHAR(40)   NOT NULL,
    quote_version             INTEGER       NOT NULL,
    status                    VARCHAR(30)   NOT NULL,
    pricing_config_id         UUID REFERENCES pricing_configs (id),
    vehicle_type_code         VARCHAR(40) REFERENCES vehicle_types (code),
    vehicle_count             INTEGER       NOT NULL DEFAULT 1,
    distance_km               NUMERIC(10,2),
    distance_source           VARCHAR(20),
    route_provider            VARCHAR(30),
    route_duration_minutes    INTEGER,
    base_price                NUMERIC(14,2),
    distance_price            NUMERIC(14,2),
    weight_surcharge          NUMERIC(14,2),
    volume_surcharge          NUMERIC(14,2),
    service_surcharge         NUMERIC(14,2),
    additional_stop_surcharge NUMERIC(14,2),
    time_surcharge            NUMERIC(14,2),
    other_surcharge           NUMERIC(14,2),
    total_price               NUMERIC(14,2),
    currency                  CHAR(3)       NOT NULL DEFAULT 'VND',
    review_reason_code        VARCHAR(40),
    review_reason             VARCHAR(500),
    rejection_reason          VARCHAR(500),
    adjustment_reason         VARCHAR(500),
    adjusted_by               UUID REFERENCES users (id),
    pricing_snapshot          JSONB,
    created_by                UUID          NOT NULL REFERENCES users (id),
    expires_at                TIMESTAMPTZ,
    accepted_at               TIMESTAMPTZ,
    rejected_at               TIMESTAMPTZ,
    superseded_at             TIMESTAMPTZ,
    created_at                TIMESTAMPTZ   NOT NULL,
    updated_at                TIMESTAMPTZ   NOT NULL,
    version                   BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_shipment_quotes_code UNIQUE (quote_code),
    CONSTRAINT uq_shipment_quotes_version UNIQUE (shipment_id, quote_version),
    CONSTRAINT chk_shipment_quotes_status CHECK (status IN ('PENDING', 'CALCULATED', 'AWAITING_MANUAL_REVIEW', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'SUPERSEDED')),
    CONSTRAINT chk_shipment_quotes_version CHECK (quote_version > 0),
    CONSTRAINT chk_shipment_quotes_distance CHECK (distance_km IS NULL OR distance_km > 0),
    CONSTRAINT chk_shipment_quotes_distance_source CHECK (distance_source IS NULL OR distance_source IN ('PROVIDER', 'MANUAL')),
    CONSTRAINT chk_shipment_quotes_currency CHECK (currency = 'VND'),
    CONSTRAINT chk_shipment_quotes_amounts CHECK (total_price IS NULL OR (total_price >= 0 AND base_price >= 0 AND distance_price >= 0
        AND weight_surcharge >= 0 AND volume_surcharge >= 0 AND service_surcharge >= 0 AND additional_stop_surcharge >= 0 AND time_surcharge >= 0)),
    -- A priced quote has every component and the total equals their sum.
    CONSTRAINT chk_shipment_quotes_total CHECK (total_price IS NULL OR total_price = base_price + distance_price + weight_surcharge + volume_surcharge
        + service_surcharge + additional_stop_surcharge + time_surcharge + other_surcharge),
    CONSTRAINT chk_shipment_quotes_priced CHECK (status NOT IN ('CALCULATED', 'ACCEPTED') OR (total_price IS NOT NULL AND distance_km IS NOT NULL AND expires_at IS NOT NULL)),
    CONSTRAINT chk_shipment_quotes_accepted CHECK (status <> 'ACCEPTED' OR accepted_at IS NOT NULL)
);
-- Only one current quote per shipment.
CREATE UNIQUE INDEX uq_shipment_quotes_current ON shipment_quotes (shipment_id) WHERE status IN ('PENDING', 'CALCULATED', 'AWAITING_MANUAL_REVIEW', 'ACCEPTED');
CREATE INDEX idx_shipment_quotes_status ON shipment_quotes (status, created_at);

CREATE TABLE shipment_quote_items (
    id          UUID PRIMARY KEY,
    quote_id    UUID          NOT NULL REFERENCES shipment_quotes (id),
    line_no     INTEGER       NOT NULL,
    component   VARCHAR(20)   NOT NULL,
    item_code   VARCHAR(60)   NOT NULL,
    item_name   VARCHAR(150)  NOT NULL,
    description VARCHAR(300),
    quantity    NUMERIC(14,3) NOT NULL,
    unit_price  NUMERIC(14,2) NOT NULL,
    amount      NUMERIC(14,2) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_shipment_quote_items_line UNIQUE (quote_id, line_no),
    CONSTRAINT chk_shipment_quote_items_component CHECK (component IN ('BASE', 'DISTANCE', 'WEIGHT', 'VOLUME', 'SERVICE', 'STOP', 'TIME', 'OTHER')),
    CONSTRAINT chk_shipment_quote_items_amount CHECK (amount = round(amount) AND quantity >= 0)
);
CREATE INDEX idx_shipment_quote_items_quote ON shipment_quote_items (quote_id);
