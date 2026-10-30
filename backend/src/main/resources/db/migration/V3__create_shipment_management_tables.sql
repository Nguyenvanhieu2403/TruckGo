-- Sprint 3: shipment management (orders, stops, cargo items, extra services, attachments, status history)
-- plus the catalogs the order wizard is built from. Prices are NOT computed in this sprint.

-- ---------------------------------------------------------------- catalogs
CREATE TABLE item_categories (
    code        VARCHAR(40)  PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(300),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Payload classes a customer can request. Nominal payload only: legal/actual capacity is checked when a driver's
-- vehicle is matched (Sprint 5), never inferred from this table alone.
CREATE TABLE vehicle_types (
    code            VARCHAR(40)   PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL,
    max_payload_kg  NUMERIC(10,2) NOT NULL CHECK (max_payload_kg > 0),
    cargo_length_cm NUMERIC(8,2)  CHECK (cargo_length_cm > 0),
    cargo_width_cm  NUMERIC(8,2)  CHECK (cargo_width_cm > 0),
    cargo_height_cm NUMERIC(8,2)  CHECK (cargo_height_cm > 0),
    sort_order      INTEGER       NOT NULL DEFAULT 0,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE vehicle_body_types (
    code       VARCHAR(40)  PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    sort_order INTEGER      NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- value_type: BOOLEAN (value "true"), OPTION (one of shipment_service_options), NUMBER (integer), TEXT (free text).
-- Services sharing an exclusive_group cannot be selected together (e.g. elevator available / unavailable).
CREATE TABLE shipment_service_definitions (
    code            VARCHAR(40)  PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    group_code      VARCHAR(20)  NOT NULL,
    value_type      VARCHAR(10)  NOT NULL,
    description     VARCHAR(300),
    exclusive_group VARCHAR(40),
    min_value       INTEGER,
    max_value       INTEGER,
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_service_group CHECK (group_code IN ('LOADING', 'SITE', 'GOODS', 'OTHER')),
    CONSTRAINT chk_service_value_type CHECK (value_type IN ('BOOLEAN', 'OPTION', 'NUMBER', 'TEXT'))
);

CREATE TABLE shipment_service_options (
    service_code VARCHAR(40)  NOT NULL REFERENCES shipment_service_definitions (code),
    value        VARCHAR(40)  NOT NULL,
    label        VARCHAR(100) NOT NULL,
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (service_code, value)
);

-- ---------------------------------------------------------------- shipments
CREATE TABLE shipments (
    id                  UUID PRIMARY KEY,
    shipment_code       VARCHAR(20)   NOT NULL,
    customer_id         UUID          NOT NULL REFERENCES users (id),
    status              VARCHAR(20)   NOT NULL,
    pickup_time         TIMESTAMPTZ,
    delivery_deadline   TIMESTAMPTZ,
    vehicle_type_code   VARCHAR(40)   REFERENCES vehicle_types (code),
    body_type_code      VARCHAR(40)   REFERENCES vehicle_body_types (code),
    vehicle_count       INTEGER       NOT NULL DEFAULT 1,
    minimum_payload_kg  NUMERIC(10,2),
    min_cargo_length_cm NUMERIC(8,2),
    min_cargo_width_cm  NUMERIC(8,2),
    min_cargo_height_cm NUMERIC(8,2),
    total_quantity      INTEGER       NOT NULL DEFAULT 0,
    total_weight_kg     NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_volume_m3     NUMERIC(14,6) NOT NULL DEFAULT 0,
    customer_note       VARCHAR(1000),
    estimated_price     NUMERIC(14,2),
    final_price         NUMERIC(14,2),
    published_at        TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    cancellation_reason VARCHAR(500),
    deleted_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_shipments_code UNIQUE (shipment_code),
    CONSTRAINT chk_shipments_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ASSIGNED', 'DRIVER_ARRIVED', 'PICKED_UP', 'IN_TRANSIT',
                                                      'DELIVERED', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_shipments_totals CHECK (total_quantity >= 0 AND total_weight_kg >= 0 AND total_volume_m3 >= 0),
    CONSTRAINT chk_shipments_vehicle_count CHECK (vehicle_count BETWEEN 1 AND 20),
    CONSTRAINT chk_shipments_requirements CHECK ((minimum_payload_kg IS NULL OR minimum_payload_kg > 0)
        AND (min_cargo_length_cm IS NULL OR min_cargo_length_cm > 0)
        AND (min_cargo_width_cm IS NULL OR min_cargo_width_cm > 0)
        AND (min_cargo_height_cm IS NULL OR min_cargo_height_cm > 0)),
    CONSTRAINT chk_shipments_prices CHECK ((estimated_price IS NULL OR estimated_price >= 0) AND (final_price IS NULL OR final_price >= 0)),
    CONSTRAINT chk_shipments_time_window CHECK (delivery_deadline IS NULL OR pickup_time IS NULL OR delivery_deadline > pickup_time),
    -- Pickup cannot be in the past at the moment the order is published.
    CONSTRAINT chk_shipments_published CHECK (status = 'DRAFT' OR status = 'CANCELLED' OR
        (published_at IS NOT NULL AND pickup_time IS NOT NULL AND pickup_time >= published_at)),
    CONSTRAINT chk_shipments_cancelled CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL)),
    CONSTRAINT chk_shipments_deleted CHECK (deleted_at IS NULL OR status = 'DRAFT')
);
CREATE INDEX idx_shipments_customer_created ON shipments (customer_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_shipments_status_pickup ON shipments (status, pickup_time) WHERE deleted_at IS NULL;
CREATE INDEX idx_shipments_created ON shipments (created_at);

CREATE TABLE shipment_stops (
    id             UUID PRIMARY KEY,
    shipment_id    UUID         NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    stop_type      VARCHAR(10)  NOT NULL,
    sequence_no    INTEGER      NOT NULL,
    contact_name   VARCHAR(100) NOT NULL,
    contact_phone  VARCHAR(20)  NOT NULL,
    province       VARCHAR(100) NOT NULL,
    district       VARCHAR(100),
    ward           VARCHAR(100),
    address_detail VARCHAR(255) NOT NULL,
    full_address   VARCHAR(600) NOT NULL,
    latitude       NUMERIC(9,6),
    longitude      NUMERIC(9,6),
    note           VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_shipment_stops_sequence UNIQUE (shipment_id, sequence_no),
    CONSTRAINT chk_shipment_stops_type CHECK (stop_type IN ('PICKUP', 'DELIVERY')),
    CONSTRAINT chk_shipment_stops_sequence CHECK (sequence_no > 0),
    CONSTRAINT chk_shipment_stops_lat CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_shipment_stops_lng CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    CONSTRAINT chk_shipment_stops_coords CHECK ((latitude IS NULL) = (longitude IS NULL))
);
CREATE INDEX idx_shipment_stops_shipment ON shipment_stops (shipment_id);

CREATE TABLE shipment_items (
    id                 UUID PRIMARY KEY,
    shipment_id        UUID          NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    item_name          VARCHAR(150)  NOT NULL,
    category_code      VARCHAR(40)   NOT NULL REFERENCES item_categories (code),
    description        VARCHAR(500),
    quantity           INTEGER       NOT NULL,
    weight_per_unit_kg NUMERIC(10,2) NOT NULL,
    length_cm          NUMERIC(8,2),
    width_cm           NUMERIC(8,2),
    height_cm          NUMERIC(8,2),
    total_weight_kg    NUMERIC(12,2) NOT NULL,
    total_volume_m3    NUMERIC(14,6),
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT chk_shipment_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_shipment_items_weight CHECK (weight_per_unit_kg >= 0 AND total_weight_kg >= 0),
    CONSTRAINT chk_shipment_items_dimensions CHECK ((length_cm IS NULL OR length_cm > 0) AND (width_cm IS NULL OR width_cm > 0) AND (height_cm IS NULL OR height_cm > 0)),
    CONSTRAINT chk_shipment_items_volume CHECK (total_volume_m3 IS NULL OR total_volume_m3 >= 0)
);
CREATE INDEX idx_shipment_items_shipment ON shipment_items (shipment_id);

CREATE TABLE shipment_services (
    id            UUID PRIMARY KEY,
    shipment_id   UUID         NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    service_code  VARCHAR(40)  NOT NULL REFERENCES shipment_service_definitions (code),
    service_name  VARCHAR(100) NOT NULL,
    service_value VARCHAR(500) NOT NULL,
    description   VARCHAR(300),
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_shipment_services UNIQUE (shipment_id, service_code)
);

CREATE TABLE shipment_attachments (
    id           UUID PRIMARY KEY,
    shipment_id  UUID         NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    file_name    VARCHAR(255) NOT NULL,
    file_key     VARCHAR(300) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size    BIGINT       NOT NULL,
    file_type    VARCHAR(20)  NOT NULL,
    uploaded_by  UUID         NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_shipment_attachments_key UNIQUE (file_key),
    CONSTRAINT chk_shipment_attachments_size CHECK (file_size > 0),
    CONSTRAINT chk_shipment_attachments_type CHECK (file_type IN ('GOODS_PHOTO')),
    CONSTRAINT chk_shipment_attachments_content CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp'))
);
CREATE INDEX idx_shipment_attachments_shipment ON shipment_attachments (shipment_id);

-- Append-only: the application never updates or deletes history rows.
CREATE TABLE shipment_status_histories (
    id            UUID PRIMARY KEY,
    shipment_id   UUID        NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    old_status    VARCHAR(20),
    new_status    VARCHAR(20) NOT NULL,
    changed_by    UUID        NOT NULL REFERENCES users (id),
    change_reason VARCHAR(500),
    created_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_shipment_status_histories_shipment ON shipment_status_histories (shipment_id, created_at);

-- ---------------------------------------------------------------- seed data
INSERT INTO item_categories (code, name, sort_order) VALUES
    ('CONSUMER_GOODS', 'Hàng tiêu dùng', 10),
    ('FURNITURE', 'Đồ nội thất', 20),
    ('ELECTRONICS', 'Thiết bị điện tử', 30),
    ('MACHINERY', 'Máy móc', 40),
    ('CONSTRUCTION_MATERIALS', 'Vật liệu xây dựng', 50),
    ('AGRICULTURAL', 'Nông sản', 60),
    ('FOOD', 'Thực phẩm', 70),
    ('FRAGILE', 'Hàng dễ vỡ', 80),
    ('BULKY', 'Hàng cồng kềnh', 90),
    ('OTHER', 'Hàng khác', 100);

-- Typical cargo box sizes are indicative only and vary by manufacturer.
INSERT INTO vehicle_types (code, name, max_payload_kg, cargo_length_cm, cargo_width_cm, cargo_height_cm, sort_order) VALUES
    ('TRUCK_500KG', 'Xe tải 500 kg', 500, 200, 130, 130, 10),
    ('TRUCK_1T', 'Xe tải 1 tấn', 1000, 300, 160, 160, 20),
    ('TRUCK_1_5T', 'Xe tải 1,5 tấn', 1500, 330, 170, 170, 30),
    ('TRUCK_2_5T', 'Xe tải 2,5 tấn', 2500, 430, 190, 190, 40),
    ('TRUCK_3_5T', 'Xe tải 3,5 tấn', 3500, 500, 200, 200, 50),
    ('TRUCK_5T', 'Xe tải 5 tấn', 5000, 620, 220, 220, 60),
    ('TRUCK_8T', 'Xe tải 8 tấn', 8000, 750, 235, 240, 70),
    ('TRUCK_10T', 'Xe tải 10 tấn', 10000, 850, 240, 250, 80),
    ('TRUCK_15T', 'Xe tải 15 tấn', 15000, 950, 240, 260, 90),
    ('TRUCK_OVER_15T', 'Xe tải trên 15 tấn', 30000, NULL, NULL, NULL, 100);

INSERT INTO vehicle_body_types (code, name, sort_order) VALUES
    ('ANY', 'Không yêu cầu', 0),
    ('OPEN', 'Thùng lửng', 10),
    ('TARPAULIN', 'Thùng mui bạt', 20),
    ('CLOSED_BOX', 'Thùng kín', 30),
    ('REFRIGERATED', 'Thùng đông lạnh', 40),
    ('CONTAINER', 'Container', 50),
    ('FLATBED', 'Sàn phẳng / chở máy móc', 60);

INSERT INTO shipment_service_definitions (code, name, group_code, value_type, description, exclusive_group, min_value, max_value, sort_order) VALUES
    ('LOADING_SUPPORT', 'Hỗ trợ bốc hàng', 'LOADING', 'OPTION', 'Nhân sự bốc hàng tại điểm lấy', NULL, NULL, NULL, 10),
    ('UNLOADING_SUPPORT', 'Hỗ trợ dỡ hàng', 'LOADING', 'OPTION', 'Nhân sự dỡ hàng tại điểm giao', NULL, NULL, NULL, 20),
    ('ELEVATOR_AVAILABLE', 'Có thang máy', 'SITE', 'BOOLEAN', NULL, 'ELEVATOR', NULL, NULL, 30),
    ('ELEVATOR_UNAVAILABLE', 'Không có thang máy', 'SITE', 'BOOLEAN', NULL, 'ELEVATOR', NULL, NULL, 40),
    ('UPSTAIRS_DELIVERY', 'Vận chuyển lên tầng', 'SITE', 'NUMBER', 'Số tầng cần vận chuyển lên', NULL, 1, 100, 50),
    ('SMALL_ALLEY', 'Đường vào nhỏ', 'SITE', 'BOOLEAN', NULL, NULL, NULL, NULL, 60),
    ('NO_DOOR_ACCESS', 'Xe không thể vào tận cửa', 'SITE', 'BOOLEAN', NULL, NULL, NULL, NULL, 70),
    ('EASY_PARKING', 'Có vị trí đỗ xe thuận tiện', 'SITE', 'BOOLEAN', NULL, NULL, NULL, NULL, 80),
    ('FRAGILE_GOODS', 'Hàng dễ vỡ', 'GOODS', 'BOOLEAN', NULL, NULL, NULL, NULL, 90),
    ('WATERPROOF_REQUIRED', 'Cần chống ẩm', 'GOODS', 'BOOLEAN', NULL, NULL, NULL, NULL, 100),
    ('COVER_REQUIRED', 'Cần che chắn', 'GOODS', 'BOOLEAN', NULL, NULL, NULL, NULL, 110),
    ('HIGH_VALUE_GOODS', 'Hàng có giá trị cao', 'GOODS', 'BOOLEAN', NULL, NULL, NULL, NULL, 120),
    ('COLD_CHAIN', 'Cần giữ lạnh', 'GOODS', 'BOOLEAN', NULL, NULL, NULL, NULL, 130),
    ('OVERSIZED_GOODS', 'Hàng cồng kềnh', 'GOODS', 'BOOLEAN', NULL, NULL, NULL, NULL, 140),
    ('SAME_DAY_DELIVERY', 'Giao trong ngày', 'OTHER', 'BOOLEAN', NULL, NULL, NULL, NULL, 150),
    ('SCHEDULED_DELIVERY', 'Giao theo lịch hẹn', 'OTHER', 'BOOLEAN', NULL, NULL, NULL, NULL, 160),
    ('MULTI_STOP_DELIVERY', 'Giao nhiều điểm', 'OTHER', 'BOOLEAN', NULL, NULL, NULL, NULL, 170),
    ('PROOF_OF_DELIVERY_PHOTO', 'Cần ảnh xác nhận giao hàng', 'OTHER', 'BOOLEAN', NULL, NULL, NULL, NULL, 180),
    ('SPECIAL_REQUIREMENT', 'Yêu cầu đặc biệt', 'OTHER', 'TEXT', 'Mô tả yêu cầu đặc biệt', NULL, NULL, NULL, 190);

INSERT INTO shipment_service_options (service_code, value, label, sort_order) VALUES
    ('LOADING_SUPPORT', 'ONE_PERSON', 'Cần một người hỗ trợ', 10),
    ('LOADING_SUPPORT', 'TWO_PEOPLE', 'Cần hai người hỗ trợ', 20),
    ('LOADING_SUPPORT', 'TEAM', 'Cần đội bốc xếp', 30),
    ('UNLOADING_SUPPORT', 'ONE_PERSON', 'Cần một người hỗ trợ', 10),
    ('UNLOADING_SUPPORT', 'TWO_PEOPLE', 'Cần hai người hỗ trợ', 20),
    ('UNLOADING_SUPPORT', 'TEAM', 'Cần đội bốc xếp', 30);
