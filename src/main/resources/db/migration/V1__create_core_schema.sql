-- EquipOps initial operational schema (MySQL 8.0.16+).
-- No seed data, DROP statements, or schema creation are performed here.

CREATE TABLE roles (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    role_code VARCHAR(40) NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_roles_role_code UNIQUE (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NULL,
    phone VARCHAR(32) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_users_account UNIQUE (account),
    CONSTRAINT uk_users_email UNIQUE (email),
    KEY idx_users_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, role_id),
    KEY idx_user_roles_role (role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE equipment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    asset_code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    model VARCHAR(120) NULL,
    location VARCHAR(160) NULL,
    category VARCHAR(100) NULL,
    status ENUM('RUNNING', 'NEEDS_ATTENTION', 'STOPPED') NOT NULL DEFAULT 'RUNNING',
    health TINYINT UNSIGNED NULL,
    next_maintenance_at DATE NULL,
    runtime_hours DECIMAL(14,2) UNSIGNED NULL,
    image_ref VARCHAR(500) NULL,
    installed_at DATE NULL,
    manager_user_id BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_equipment_asset_code UNIQUE (asset_code),
    CONSTRAINT chk_equipment_health CHECK (health IS NULL OR health <= 100),
    KEY idx_equipment_status_location (status, location),
    KEY idx_equipment_manager (manager_user_id),
    CONSTRAINT fk_equipment_manager FOREIGN KEY (manager_user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE work_orders (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    equipment_id BIGINT UNSIGNED NOT NULL,
    description TEXT NULL,
    priority ENUM('URGENT', 'HIGH', 'NORMAL') NOT NULL DEFAULT 'NORMAL',
    status ENUM('PENDING', 'IN_PROGRESS', 'AWAITING_ACCEPTANCE', 'COMPLETED') NOT NULL DEFAULT 'PENDING',
    assignee_user_id BIGINT UNSIGNED NULL,
    due_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_work_orders_order_no UNIQUE (order_no),
    KEY idx_work_orders_status_priority_due (status, priority, due_at),
    KEY idx_work_orders_equipment_status (equipment_id, status),
    KEY idx_work_orders_assignee_status (assignee_user_id, status),
    CONSTRAINT fk_work_orders_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_orders_assignee FOREIGN KEY (assignee_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_work_orders_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE work_order_logs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    work_order_id BIGINT UNSIGNED NOT NULL,
    event_type ENUM('CREATED', 'STATUS_CHANGED', 'COMMENT', 'ASSIGNED', 'UPDATED') NOT NULL,
    from_status ENUM('PENDING', 'IN_PROGRESS', 'AWAITING_ACCEPTANCE', 'COMPLETED') NULL,
    to_status ENUM('PENDING', 'IN_PROGRESS', 'AWAITING_ACCEPTANCE', 'COMPLETED') NULL,
    title VARCHAR(160) NOT NULL,
    detail TEXT NULL,
    actor_user_id BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_work_order_logs_order_time (work_order_id, created_at),
    KEY idx_work_order_logs_actor_time (actor_user_id, created_at),
    CONSTRAINT fk_work_order_logs_order FOREIGN KEY (work_order_id) REFERENCES work_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_work_order_logs_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE spare_parts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    part_code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    spec VARCHAR(160) NULL,
    category VARCHAR(100) NULL,
    location VARCHAR(120) NULL,
    stock_quantity DECIMAL(14,3) UNSIGNED NOT NULL DEFAULT 0,
    min_stock_quantity DECIMAL(14,3) UNSIGNED NOT NULL DEFAULT 0,
    unit VARCHAR(24) NOT NULL DEFAULT '件',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_spare_parts_code UNIQUE (part_code),
    CONSTRAINT chk_spare_parts_stock_nonnegative CHECK (stock_quantity >= 0),
    CONSTRAINT chk_spare_parts_min_stock_nonnegative CHECK (min_stock_quantity >= 0),
    KEY idx_spare_parts_stock_risk (enabled, stock_quantity, min_stock_quantity),
    KEY idx_spare_parts_category_location (category, location)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_transactions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    transaction_no VARCHAR(40) NOT NULL,
    spare_part_id BIGINT UNSIGNED NOT NULL,
    transaction_type ENUM('INBOUND', 'OUTBOUND', 'ADJUSTMENT') NOT NULL,
    quantity DECIMAL(14,3) NOT NULL,
    reference_type ENUM('WORK_ORDER', 'PURCHASE_ORDER', 'MANUAL', 'OTHER') NOT NULL DEFAULT 'MANUAL',
    reference_text VARCHAR(160) NULL,
    work_order_id BIGINT UNSIGNED NULL,
    created_by BIGINT UNSIGNED NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_transactions_no UNIQUE (transaction_no),
    CONSTRAINT chk_inventory_transaction_quantity CHECK (quantity <> 0),
    CONSTRAINT chk_inventory_transaction_direction CHECK (
        (transaction_type IN ('INBOUND', 'OUTBOUND') AND quantity > 0)
        OR transaction_type = 'ADJUSTMENT'
    ),
    KEY idx_inventory_transactions_part_time (spare_part_id, created_at),
    KEY idx_inventory_transactions_work_order_time (work_order_id, created_at),
    KEY idx_inventory_transactions_creator_time (created_by, created_at),
    CONSTRAINT fk_inventory_transactions_part FOREIGN KEY (spare_part_id) REFERENCES spare_parts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_transactions_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders (id) ON DELETE SET NULL,
    CONSTRAINT fk_inventory_transactions_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE work_order_parts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    work_order_id BIGINT UNSIGNED NOT NULL,
    spare_part_id BIGINT UNSIGNED NOT NULL,
    planned_quantity DECIMAL(14,3) NOT NULL DEFAULT 0,
    issued_quantity DECIMAL(14,3) NOT NULL DEFAULT 0,
    returned_quantity DECIMAL(14,3) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_work_order_parts_order_part UNIQUE (work_order_id, spare_part_id),
    CONSTRAINT chk_work_order_parts_quantities CHECK (planned_quantity >= 0 AND issued_quantity >= 0 AND returned_quantity >= 0),
    KEY idx_work_order_parts_part (spare_part_id),
    CONSTRAINT fk_work_order_parts_order FOREIGN KEY (work_order_id) REFERENCES work_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_work_order_parts_part FOREIGN KEY (spare_part_id) REFERENCES spare_parts (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
