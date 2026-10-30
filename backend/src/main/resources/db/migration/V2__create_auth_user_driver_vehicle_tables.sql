-- Sprint 2: accounts, refresh sessions, driver profiles, vehicles and admin audit log.
-- All timestamps are TIMESTAMPTZ and written in UTC by the application.

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(254),
    phone           VARCHAR(20),
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(100) NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    status          VARCHAR(30)  NOT NULL,
    email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    phone_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    last_login_at   TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_users_contact CHECK (email IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT chk_users_email_normalized CHECK (email IS NULL OR email = lower(btrim(email))),
    CONSTRAINT chk_users_phone_format CHECK (phone IS NULL OR phone ~ '^\+84[0-9]{9}$'),
    CONSTRAINT chk_users_role CHECK (role IN ('CUSTOMER', 'DRIVER', 'ADMIN')),
    CONSTRAINT chk_users_status CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'LOCKED'))
);
-- Unique indexes allow many NULLs, so either contact field may be omitted.
CREATE UNIQUE INDEX uq_users_email ON users (email);
CREATE UNIQUE INDEX uq_users_phone ON users (phone);
CREATE INDEX idx_users_role ON users (role);
CREATE INDEX idx_users_status ON users (status);

CREATE TABLE auth_sessions (
    id                     UUID PRIMARY KEY,
    user_id                UUID        NOT NULL REFERENCES users (id),
    family_id              UUID        NOT NULL,
    token_hash             VARCHAR(64) NOT NULL,
    expires_at             TIMESTAMPTZ NOT NULL,
    revoked_at             TIMESTAMPTZ,
    revoke_reason          VARCHAR(30),
    replaced_by_session_id UUID REFERENCES auth_sessions (id),
    created_at             TIMESTAMPTZ NOT NULL,
    last_used_at           TIMESTAMPTZ,
    user_agent             VARCHAR(255),
    CONSTRAINT uq_auth_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT chk_auth_sessions_revoke_reason CHECK (revoke_reason IS NULL OR revoke_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'PASSWORD_CHANGED', 'ACCOUNT_DISABLED'))
);
CREATE INDEX idx_auth_sessions_user ON auth_sessions (user_id);
CREATE INDEX idx_auth_sessions_family ON auth_sessions (family_id);
CREATE INDEX idx_auth_sessions_expires ON auth_sessions (expires_at);

CREATE TABLE driver_profiles (
    id                  UUID PRIMARY KEY,
    user_id             UUID        NOT NULL REFERENCES users (id),
    license_number      VARCHAR(20),
    license_class       VARCHAR(5),
    verification_status VARCHAR(20) NOT NULL,
    rejection_reason    VARCHAR(500),
    submitted_at        TIMESTAMPTZ,
    reviewed_at         TIMESTAMPTZ,
    reviewed_by         UUID REFERENCES users (id),
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_driver_profiles_user UNIQUE (user_id),
    CONSTRAINT chk_driver_profiles_status CHECK (verification_status IN ('NOT_SUBMITTED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED'))
);
CREATE UNIQUE INDEX uq_driver_profiles_license ON driver_profiles (license_number);
CREATE INDEX idx_driver_profiles_status ON driver_profiles (verification_status, submitted_at);

CREATE TABLE vehicles (
    id                       UUID PRIMARY KEY,
    driver_id                UUID         NOT NULL REFERENCES driver_profiles (id),
    plate_number             VARCHAR(15)  NOT NULL,
    vehicle_type             VARCHAR(30)  NOT NULL,
    max_payload_kg           INTEGER      NOT NULL,
    length_cm                INTEGER      NOT NULL,
    width_cm                 INTEGER      NOT NULL,
    height_cm                INTEGER      NOT NULL,
    registration_expiry_date DATE         NOT NULL,
    verification_status      VARCHAR(20)  NOT NULL,
    rejection_reason         VARCHAR(500),
    submitted_at             TIMESTAMPTZ,
    reviewed_at              TIMESTAMPTZ,
    reviewed_by              UUID REFERENCES users (id),
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_vehicles_plate UNIQUE (plate_number),
    CONSTRAINT chk_vehicles_positive CHECK (max_payload_kg > 0 AND length_cm > 0 AND width_cm > 0 AND height_cm > 0),
    CONSTRAINT chk_vehicles_type CHECK (vehicle_type IN ('VAN', 'SMALL_TRUCK', 'MEDIUM_TRUCK', 'LARGE_TRUCK', 'CONTAINER_TRUCK', 'REFRIGERATED_TRUCK')),
    CONSTRAINT chk_vehicles_status CHECK (verification_status IN ('NOT_SUBMITTED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED'))
);
CREATE INDEX idx_vehicles_driver ON vehicles (driver_id);
CREATE INDEX idx_vehicles_status ON vehicles (verification_status, submitted_at);

CREATE TABLE admin_audit_logs (
    id          UUID PRIMARY KEY,
    actor_id    UUID        NOT NULL REFERENCES users (id),
    action      VARCHAR(50) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id   UUID        NOT NULL,
    details     VARCHAR(1000),
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_admin_audit_logs_target ON admin_audit_logs (target_type, target_id);
CREATE INDEX idx_admin_audit_logs_actor ON admin_audit_logs (actor_id, created_at);
