-- Sprint 6: trip execution. Stop-by-stop progress, GPS tracking, delivery confirmation by OTP, incidents.

-- ---------------------------------------------------------------- trip milestones on the order
ALTER TABLE shipments
    ADD COLUMN driver_arrived_at TIMESTAMPTZ,
    ADD COLUMN picked_up_at      TIMESTAMPTZ,
    ADD COLUMN in_transit_at     TIMESTAMPTZ,
    ADD COLUMN delivered_at      TIMESTAMPTZ,
    ADD COLUMN completed_at      TIMESTAMPTZ;

ALTER TABLE shipments ADD CONSTRAINT chk_shipments_trip_milestones CHECK (
    (status <> 'DRIVER_ARRIVED' OR driver_arrived_at IS NOT NULL)
    AND (status <> 'PICKED_UP' OR picked_up_at IS NOT NULL)
    AND (status <> 'IN_TRANSIT' OR in_transit_at IS NOT NULL)
    AND (status <> 'DELIVERED' OR delivered_at IS NOT NULL)
    AND (status <> 'COMPLETED' OR completed_at IS NOT NULL));

-- Proof photos taken by the driver live next to the customer's goods photos.
ALTER TABLE shipment_attachments DROP CONSTRAINT chk_shipment_attachments_type;
ALTER TABLE shipment_attachments ADD CONSTRAINT chk_shipment_attachments_type
    CHECK (file_type IN ('GOODS_PHOTO', 'PICKUP_PHOTO', 'DELIVERY_PHOTO', 'INCIDENT_PHOTO'));
CREATE INDEX idx_shipment_attachments_type ON shipment_attachments (shipment_id, file_type);

-- ---------------------------------------------------------------- stop progress
CREATE TABLE shipment_stop_progress (
    id               UUID PRIMARY KEY,
    shipment_id      UUID          NOT NULL REFERENCES shipments (id),
    shipment_stop_id UUID          NOT NULL REFERENCES shipment_stops (id),
    driver_id        UUID          NOT NULL REFERENCES driver_profiles (id),
    sequence_no      INTEGER       NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    arrived_at       TIMESTAMPTZ,
    departed_at      TIMESTAMPTZ,
    note             VARCHAR(500),
    latitude         NUMERIC(9,6),
    longitude        NUMERIC(9,6),
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_stop_progress_stop UNIQUE (shipment_stop_id),
    CONSTRAINT uq_stop_progress_sequence UNIQUE (shipment_id, sequence_no),
    CONSTRAINT chk_stop_progress_status CHECK (status IN ('PENDING', 'ARRIVED', 'COMPLETED', 'SKIPPED')),
    CONSTRAINT chk_stop_progress_times CHECK (
        (status = 'PENDING' AND arrived_at IS NULL AND departed_at IS NULL)
        OR (status = 'ARRIVED' AND arrived_at IS NOT NULL AND departed_at IS NULL)
        OR (status = 'COMPLETED' AND arrived_at IS NOT NULL AND departed_at IS NOT NULL AND departed_at >= arrived_at)
        OR status = 'SKIPPED'),
    CONSTRAINT chk_stop_progress_coordinates CHECK ((latitude IS NULL) = (longitude IS NULL)
        AND (latitude IS NULL OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)))
);
CREATE INDEX idx_stop_progress_shipment ON shipment_stop_progress (shipment_id, sequence_no);

-- ---------------------------------------------------------------- GPS
-- One row per accepted fix. Full history lives here; Redis only keeps the latest point. Old rows of finished trips
-- are deleted by the retention job (app.tracking.retention).
CREATE TABLE shipment_tracking_locations (
    id          UUID PRIMARY KEY,
    shipment_id UUID          NOT NULL REFERENCES shipments (id),
    driver_id   UUID          NOT NULL REFERENCES driver_profiles (id),
    latitude    NUMERIC(9,6)  NOT NULL,
    longitude   NUMERIC(9,6)  NOT NULL,
    accuracy    NUMERIC(8,2),
    speed       NUMERIC(6,2),
    heading     NUMERIC(5,2),
    recorded_at TIMESTAMPTZ   NOT NULL,
    received_at TIMESTAMPTZ   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_tracking_locations_recorded UNIQUE (shipment_id, recorded_at),
    CONSTRAINT chk_tracking_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_tracking_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT chk_tracking_accuracy CHECK (accuracy IS NULL OR accuracy >= 0),
    CONSTRAINT chk_tracking_speed CHECK (speed IS NULL OR speed >= 0),
    CONSTRAINT chk_tracking_heading CHECK (heading IS NULL OR (heading >= 0 AND heading < 360))
);
CREATE INDEX idx_tracking_locations_shipment_time ON shipment_tracking_locations (shipment_id, recorded_at DESC);
CREATE INDEX idx_tracking_locations_received ON shipment_tracking_locations (received_at);

-- Whether the driver is currently sharing. ACTIVE while in transit, PAUSED when the driver stops sharing, ENDED when
-- the trip is delivered, completed or cancelled.
CREATE TABLE shipment_tracking_sessions (
    shipment_id UUID PRIMARY KEY REFERENCES shipments (id),
    driver_id   UUID        NOT NULL REFERENCES driver_profiles (id),
    status      VARCHAR(20) NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    paused_at   TIMESTAMPTZ,
    ended_at    TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_tracking_sessions_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')),
    CONSTRAINT chk_tracking_sessions_ended CHECK (status <> 'ENDED' OR ended_at IS NOT NULL)
);

-- ---------------------------------------------------------------- delivery confirmation (OTP)
-- Only an HMAC of the code is stored. One PENDING confirmation per order at a time.
CREATE TABLE shipment_delivery_confirmations (
    id              UUID PRIMARY KEY,
    shipment_id     UUID         NOT NULL REFERENCES shipments (id),
    otp_hash        VARCHAR(128) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    failed_attempts INTEGER      NOT NULL DEFAULT 0,
    max_attempts    INTEGER      NOT NULL,
    send_count      INTEGER      NOT NULL DEFAULT 1,
    last_sent_at    TIMESTAMPTZ  NOT NULL,
    confirmed_at    TIMESTAMPTZ,
    confirmed_by    UUID REFERENCES users (id),
    note            VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_delivery_confirmations_status CHECK (status IN ('PENDING', 'CONFIRMED', 'LOCKED', 'SUPERSEDED')),
    CONSTRAINT chk_delivery_confirmations_attempts CHECK (failed_attempts >= 0 AND failed_attempts <= max_attempts),
    CONSTRAINT chk_delivery_confirmations_confirmed CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_delivery_confirmations_pending ON shipment_delivery_confirmations (shipment_id) WHERE status = 'PENDING';
CREATE INDEX idx_delivery_confirmations_shipment ON shipment_delivery_confirmations (shipment_id, created_at DESC);

-- ---------------------------------------------------------------- incidents
CREATE TABLE shipment_incidents (
    id              UUID PRIMARY KEY,
    shipment_id     UUID          NOT NULL REFERENCES shipments (id),
    reported_by     UUID          NOT NULL REFERENCES users (id),
    driver_id       UUID REFERENCES driver_profiles (id),
    incident_type   VARCHAR(30)   NOT NULL,
    description     VARCHAR(1000) NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    latitude        NUMERIC(9,6),
    longitude       NUMERIC(9,6),
    occurred_at     TIMESTAMPTZ   NOT NULL,
    resolution_note VARCHAR(1000),
    handled_by      UUID REFERENCES users (id),
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_incidents_type CHECK (incident_type IN ('VEHICLE_BREAKDOWN', 'TRAFFIC_JAM', 'ACCIDENT', 'GOODS_DAMAGED',
                                                           'CUSTOMER_UNAVAILABLE', 'WRONG_ADDRESS', 'WEATHER', 'OTHER')),
    CONSTRAINT chk_incidents_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CANCELLED')),
    CONSTRAINT chk_incidents_resolved CHECK (status NOT IN ('RESOLVED', 'CANCELLED') OR resolved_at IS NOT NULL),
    CONSTRAINT chk_incidents_coordinates CHECK ((latitude IS NULL) = (longitude IS NULL)
        AND (latitude IS NULL OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)))
);
CREATE INDEX idx_incidents_shipment ON shipment_incidents (shipment_id, created_at DESC);
CREATE INDEX idx_incidents_status ON shipment_incidents (status, created_at DESC);
CREATE INDEX idx_incidents_type ON shipment_incidents (incident_type, created_at DESC);

CREATE TABLE shipment_incident_attachments (
    incident_id   UUID NOT NULL REFERENCES shipment_incidents (id),
    attachment_id UUID NOT NULL REFERENCES shipment_attachments (id),
    PRIMARY KEY (incident_id, attachment_id)
);

-- Photos attached to a milestone (pickup / delivery proof).
CREATE TABLE shipment_milestone_attachments (
    shipment_id   UUID        NOT NULL REFERENCES shipments (id),
    milestone     VARCHAR(20) NOT NULL,
    attachment_id UUID        NOT NULL REFERENCES shipment_attachments (id),
    PRIMARY KEY (attachment_id),
    CONSTRAINT chk_milestone_attachments_milestone CHECK (milestone IN ('PICKUP', 'DELIVERY'))
);
CREATE INDEX idx_milestone_attachments_shipment ON shipment_milestone_attachments (shipment_id, milestone);
