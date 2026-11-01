-- Sprint 5 completion: orders needing several vehicles, schedule conflicts, vehicle body types, system actors in the
-- audit trail, removing/replacing an assigned driver, idempotent notifications.

-- ---------------------------------------------------------------- assignments (one row per vehicle on an order)
-- shipments.assigned_driver_id/assigned_vehicle_id stay as the order's lead assignment (the driver who runs the trip
-- in Sprint 6); this table holds every driver and vehicle on the order and is the source of truth for schedules.
CREATE TABLE shipment_assignments (
    id             UUID PRIMARY KEY,
    shipment_id    UUID         NOT NULL REFERENCES shipments (id),
    driver_id      UUID         NOT NULL REFERENCES driver_profiles (id),
    vehicle_id     UUID         NOT NULL REFERENCES vehicles (id),
    application_id UUID REFERENCES driver_shipment_applications (id),
    slot_no        INTEGER      NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    -- When the driver and vehicle are busy: [pickup, end of trip + buffer). NULL never blocks (only legacy rows that
    -- already overlapped before this check existed, see the backfill below).
    busy_period    TSTZRANGE,
    assigned_at    TIMESTAMPTZ  NOT NULL,
    assigned_by    UUID         NOT NULL REFERENCES users (id),
    released_at    TIMESTAMPTZ,
    released_by    UUID REFERENCES users (id),
    release_reason VARCHAR(500),
    finished_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_assignments_status CHECK (status IN ('ACTIVE', 'RELEASED', 'FINISHED')),
    CONSTRAINT chk_assignments_slot CHECK (slot_no >= 1),
    CONSTRAINT chk_assignments_released CHECK ((status = 'RELEASED') = (released_at IS NOT NULL)),
    CONSTRAINT chk_assignments_finished CHECK ((status = 'FINISHED') = (finished_at IS NOT NULL))
);

-- Backfill: every order assigned before this migration gets its single assignment. Trip end = delivery deadline, or
-- pickup + 8 hours when there is none; 1 hour buffer (the application defaults).
INSERT INTO shipment_assignments (id, shipment_id, driver_id, vehicle_id, application_id, slot_no, status, busy_period,
                                  assigned_at, assigned_by, released_at, release_reason, finished_at, created_at, updated_at)
SELECT gen_random_uuid(), s.id, s.assigned_driver_id, s.assigned_vehicle_id,
       (SELECT a.id FROM driver_shipment_applications a WHERE a.shipment_id = s.id AND a.status = 'ACCEPTED' LIMIT 1),
       1,
       CASE WHEN s.status IN ('DELIVERED', 'COMPLETED') THEN 'FINISHED'
            WHEN s.status = 'CANCELLED' THEN 'RELEASED' ELSE 'ACTIVE' END,
       tstzrange(s.pickup_time, COALESCE(GREATEST(s.delivery_deadline, s.pickup_time), s.pickup_time + INTERVAL '8 hours') + INTERVAL '1 hour', '[)'),
       s.assigned_at,
       COALESCE((SELECT h.performed_by FROM shipment_assignment_histories h
                  WHERE h.shipment_id = s.id AND h.action = 'ASSIGNED' ORDER BY h.created_at DESC LIMIT 1), s.customer_id),
       CASE WHEN s.status = 'CANCELLED' THEN COALESCE(s.cancelled_at, s.updated_at) END,
       CASE WHEN s.status = 'CANCELLED' THEN 'Đơn đã hủy' END,
       CASE WHEN s.status IN ('DELIVERED', 'COMPLETED') THEN COALESCE(s.delivered_at, s.completed_at, s.updated_at) END,
       now(), now()
  FROM shipments s
 WHERE s.assigned_driver_id IS NOT NULL AND s.assigned_vehicle_id IS NOT NULL AND s.assigned_at IS NOT NULL AND s.pickup_time IS NOT NULL;

-- Active legacy assignments that already overlap an earlier one of the same driver or vehicle keep their row but stop
-- blocking (busy_period NULL), so the constraint can be created without rewriting history. New ones are always checked.
UPDATE shipment_assignments x SET busy_period = NULL
 WHERE x.status = 'ACTIVE' AND EXISTS (
       SELECT 1 FROM shipment_assignments y
        WHERE y.status = 'ACTIVE' AND y.id <> x.id AND y.busy_period && x.busy_period
          AND (y.driver_id = x.driver_id OR y.vehicle_id = x.vehicle_id)
          AND (y.assigned_at, y.id) < (x.assigned_at, x.id));

-- A driver or vehicle can never be on two active assignments whose periods overlap: enforced by PostgreSQL, so two
-- concurrent assignments of the same driver/vehicle cannot both commit.
ALTER TABLE shipment_assignments
    ADD CONSTRAINT ex_assignments_driver_schedule EXCLUDE USING gist (driver_id WITH =, busy_period WITH &&) WHERE (status = 'ACTIVE'),
    ADD CONSTRAINT ex_assignments_vehicle_schedule EXCLUDE USING gist (vehicle_id WITH =, busy_period WITH &&) WHERE (status = 'ACTIVE');
-- Same driver or vehicle at most once per order; slots 1..vehicle_count handed out under the order's row lock.
CREATE UNIQUE INDEX uq_assignments_shipment_driver ON shipment_assignments (shipment_id, driver_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_assignments_shipment_vehicle ON shipment_assignments (shipment_id, vehicle_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_assignments_shipment_slot ON shipment_assignments (shipment_id, slot_no) WHERE status = 'ACTIVE';
CREATE INDEX idx_assignments_shipment ON shipment_assignments (shipment_id, status);
CREATE INDEX idx_assignments_driver ON shipment_assignments (driver_id, status);
CREATE INDEX idx_assignments_vehicle ON shipment_assignments (vehicle_id, status);

-- Several drivers can now be accepted for one order (one per vehicle); uniqueness moved to shipment_assignments.
DROP INDEX uq_driver_applications_accepted;
-- RELEASED: the driver was accepted and later removed from the order.
ALTER TABLE driver_shipment_applications DROP CONSTRAINT chk_driver_applications_status;
ALTER TABLE driver_shipment_applications ADD CONSTRAINT chk_driver_applications_status
    CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'RELEASED'));

-- Open multi-vehicle orders are listed too.
DROP INDEX idx_shipments_marketplace;
CREATE INDEX idx_shipments_marketplace ON shipments (pickup_time) WHERE status = 'PUBLISHED' AND deleted_at IS NULL;

-- ---------------------------------------------------------------- audit: user or system
ALTER TABLE shipment_status_histories
    ADD COLUMN actor_type   VARCHAR(10) NOT NULL DEFAULT 'USER',
    ADD COLUMN system_actor VARCHAR(60),
    ALTER COLUMN changed_by DROP NOT NULL;
-- Rows the marketplace expiry job wrote with the order owner as a stand-in actor (exact reason text of that job).
UPDATE shipment_status_histories SET actor_type = 'SYSTEM', system_actor = 'marketplace-expiry-job', changed_by = NULL
 WHERE old_status = 'PUBLISHED' AND new_status = 'EXPIRED' AND change_reason = 'Hệ thống: quá giờ lấy hàng mà chưa chọn tài xế';
ALTER TABLE shipment_status_histories ADD CONSTRAINT chk_status_histories_actor CHECK (
    (actor_type = 'USER' AND changed_by IS NOT NULL AND system_actor IS NULL)
    OR (actor_type = 'SYSTEM' AND changed_by IS NULL AND system_actor IS NOT NULL));

ALTER TABLE shipment_assignment_histories
    ADD COLUMN assignment_id UUID REFERENCES shipment_assignments (id),
    ADD COLUMN actor_type    VARCHAR(10) NOT NULL DEFAULT 'USER',
    ADD COLUMN system_actor  VARCHAR(60),
    ALTER COLUMN performed_by DROP NOT NULL;
ALTER TABLE shipment_assignment_histories DROP CONSTRAINT chk_assignment_histories_action;
ALTER TABLE shipment_assignment_histories ADD CONSTRAINT chk_assignment_histories_action CHECK (action IN ('ASSIGNED', 'UNASSIGNED', 'REPLACED'));
ALTER TABLE shipment_assignment_histories ADD CONSTRAINT chk_assignment_histories_actor CHECK (
    (actor_type = 'USER' AND performed_by IS NOT NULL AND system_actor IS NULL)
    OR (actor_type = 'SYSTEM' AND performed_by IS NULL AND system_actor IS NOT NULL));
UPDATE shipment_assignment_histories h SET assignment_id = a.id
  FROM shipment_assignments a WHERE a.shipment_id = h.shipment_id AND a.driver_id = h.new_driver_id AND h.action = 'ASSIGNED';
CREATE INDEX idx_assignment_histories_assignment ON shipment_assignment_histories (assignment_id);

-- ---------------------------------------------------------------- vehicle body type
-- Uses the existing vehicle_body_types catalog (no second enum). NULL = not declared yet (vehicles created before
-- this migration); such vehicles do not qualify for orders that require a specific body type.
ALTER TABLE vehicles
    ADD COLUMN body_type_code VARCHAR(40) REFERENCES vehicle_body_types (code),
    ADD CONSTRAINT chk_vehicles_body_type CHECK (body_type_code IS NULL OR body_type_code <> 'ANY');

-- ---------------------------------------------------------------- notifications: idempotent events
-- The same business event (e.g. "application X accepted") produces at most one notification per recipient.
ALTER TABLE notifications ADD COLUMN event_key VARCHAR(200);
CREATE UNIQUE INDEX uq_notifications_event ON notifications (recipient_user_id, event_key) WHERE event_key IS NOT NULL;
CREATE INDEX idx_notifications_recipient_created ON notifications (recipient_user_id, created_at, id);
