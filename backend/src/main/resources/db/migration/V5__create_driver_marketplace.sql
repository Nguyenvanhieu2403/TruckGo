-- Sprint 5: driver marketplace. Drivers apply for published, quote-accepted orders; the customer picks one; the
-- assignment is atomic (row lock + conditional update + unique indexes below). In-app notifications.

-- Accent/case-insensitive province and district filters ("Ha Noi" matches "Hà Nội"). Trusted extension in PG 13+.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- ---------------------------------------------------------------- assignment on the order
ALTER TABLE shipments
    ADD COLUMN assigned_driver_id  UUID REFERENCES driver_profiles (id),
    ADD COLUMN assigned_vehicle_id UUID REFERENCES vehicles (id),
    ADD COLUMN assigned_at         TIMESTAMPTZ;

-- Open orders have no driver; orders past assignment always have driver, vehicle and time.
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_assignment CHECK (
    (status IN ('DRAFT', 'PUBLISHED') AND assigned_driver_id IS NULL AND assigned_vehicle_id IS NULL AND assigned_at IS NULL)
    OR (status IN ('ASSIGNED', 'DRIVER_ARRIVED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'COMPLETED')
        AND assigned_driver_id IS NOT NULL AND assigned_vehicle_id IS NOT NULL AND assigned_at IS NOT NULL)
    OR status IN ('CANCELLED', 'EXPIRED'));

CREATE INDEX idx_shipments_marketplace ON shipments (pickup_time) WHERE status = 'PUBLISHED' AND assigned_driver_id IS NULL AND deleted_at IS NULL;
CREATE INDEX idx_shipments_assigned_driver ON shipments (assigned_driver_id, status) WHERE assigned_driver_id IS NOT NULL;
CREATE INDEX idx_shipment_stops_type ON shipment_stops (shipment_id, stop_type, sequence_no);
CREATE INDEX idx_shipment_quotes_accepted ON shipment_quotes (shipment_id) WHERE status = 'ACCEPTED';

-- ---------------------------------------------------------------- applications
CREATE TABLE driver_shipment_applications (
    id                UUID PRIMARY KEY,
    shipment_id       UUID         NOT NULL REFERENCES shipments (id),
    driver_id         UUID         NOT NULL REFERENCES driver_profiles (id),
    vehicle_id        UUID         NOT NULL REFERENCES vehicles (id),
    status            VARCHAR(20)  NOT NULL,
    driver_message    VARCHAR(500),
    response_reason   VARCHAR(500),
    applied_at        TIMESTAMPTZ  NOT NULL,
    responded_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_driver_applications_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    CONSTRAINT chk_driver_applications_responded CHECK (status = 'PENDING' OR responded_at IS NOT NULL)
);
-- One active (pending or accepted) application per driver and order; at most one accepted application per order.
CREATE UNIQUE INDEX uq_driver_applications_active ON driver_shipment_applications (shipment_id, driver_id) WHERE status IN ('PENDING', 'ACCEPTED');
CREATE UNIQUE INDEX uq_driver_applications_accepted ON driver_shipment_applications (shipment_id) WHERE status = 'ACCEPTED';
CREATE INDEX idx_driver_applications_shipment ON driver_shipment_applications (shipment_id, status, created_at);
CREATE INDEX idx_driver_applications_driver ON driver_shipment_applications (driver_id, status, created_at DESC);

-- ---------------------------------------------------------------- assignment history (append-only)
CREATE TABLE shipment_assignment_histories (
    id                 UUID PRIMARY KEY,
    shipment_id        UUID        NOT NULL REFERENCES shipments (id),
    previous_driver_id UUID REFERENCES driver_profiles (id),
    new_driver_id      UUID REFERENCES driver_profiles (id),
    vehicle_id         UUID REFERENCES vehicles (id),
    application_id     UUID REFERENCES driver_shipment_applications (id),
    action             VARCHAR(30) NOT NULL,
    reason             VARCHAR(500),
    performed_by       UUID        NOT NULL REFERENCES users (id),
    created_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_assignment_histories_action CHECK (action IN ('ASSIGNED', 'UNASSIGNED'))
);
CREATE INDEX idx_assignment_histories_shipment ON shipment_assignment_histories (shipment_id, created_at);
CREATE INDEX idx_assignment_histories_created ON shipment_assignment_histories (created_at DESC);

-- ---------------------------------------------------------------- in-app notifications
CREATE TABLE notifications (
    id                UUID PRIMARY KEY,
    recipient_user_id UUID         NOT NULL REFERENCES users (id),
    notification_type VARCHAR(50)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    content           VARCHAR(1000) NOT NULL,
    reference_type    VARCHAR(30),
    reference_id      UUID,
    is_read           BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_notifications_read CHECK (is_read = (read_at IS NOT NULL))
);
CREATE INDEX idx_notifications_recipient ON notifications (recipient_user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (recipient_user_id) WHERE NOT is_read;
