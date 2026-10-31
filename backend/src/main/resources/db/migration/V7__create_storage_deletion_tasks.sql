-- Objects that must be removed from object storage. Rows are written in the same transaction as the database change
-- that orphaned the object (photo deleted, draft deleted, order cancelled, failed upload, orphan found), so an object
-- is only ever deleted after that change committed, and a failed delete is retried instead of forgotten.
CREATE TABLE storage_deletion_tasks (
    id              UUID PRIMARY KEY,
    file_key        VARCHAR(300) NOT NULL,
    reason          VARCHAR(30)  NOT NULL,
    shipment_id     UUID,
    status          VARCHAR(20)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(300),
    created_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    CONSTRAINT chk_storage_deletion_reason CHECK (reason IN ('ATTACHMENT_DELETED', 'DRAFT_DELETED', 'SHIPMENT_CANCELLED', 'UPLOAD_COMPENSATION', 'ORPHAN')),
    CONSTRAINT chk_storage_deletion_status CHECK (status IN ('PENDING', 'DONE', 'SKIPPED', 'FAILED')),
    CONSTRAINT chk_storage_deletion_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_storage_deletion_completed CHECK ((status = 'PENDING') = (completed_at IS NULL))
);
-- One open task per object; the due index drives the retry job.
CREATE UNIQUE INDEX uq_storage_deletion_pending ON storage_deletion_tasks (file_key) WHERE status = 'PENDING';
CREATE INDEX idx_storage_deletion_due ON storage_deletion_tasks (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_storage_deletion_shipment ON storage_deletion_tasks (shipment_id);
