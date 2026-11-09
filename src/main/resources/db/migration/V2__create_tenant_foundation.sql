-- 租户与成员基础结构。MySQL 8.0.16+；仅新增表，无初始化业务数据。
-- 已有 V1 手工建表的数据库必须经核对、备份后先 baseline 为 1。
-- 已执行的迁移不可修改；不对旧设备、工单、库存自动猜测或回填租户。

CREATE TABLE tenants (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_tenants_code UNIQUE (tenant_code),
    CONSTRAINT chk_tenants_code CHECK (CHAR_LENGTH(TRIM(tenant_code)) > 0),
    CONSTRAINT chk_tenants_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_tenants_enabled CHECK (enabled IN (0, 1)),
    KEY idx_tenants_enabled_id (enabled, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tenant_memberships (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_memberships_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT uk_memberships_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT chk_memberships_enabled CHECK (enabled IN (0, 1)),
    KEY idx_memberships_user_enabled (user_id, enabled, tenant_id),
    KEY idx_memberships_tenant_enabled (tenant_id, enabled, id),
    CONSTRAINT fk_memberships_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tenant_member_roles (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT UNSIGNED NOT NULL,
    membership_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_member_roles_membership_role UNIQUE (membership_id, role_id),
    KEY idx_member_roles_tenant_membership (tenant_id, membership_id),
    KEY idx_member_roles_role (role_id),
    CONSTRAINT fk_member_roles_membership FOREIGN KEY (tenant_id, membership_id)
        REFERENCES tenant_memberships (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sites (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT UNSIGNED NOT NULL,
    site_code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    address VARCHAR(255) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    default_slot TINYINT GENERATED ALWAYS AS (CASE WHEN is_default = 1 THEN 1 ELSE NULL END) STORED,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_sites_tenant_code UNIQUE (tenant_id, site_code),
    CONSTRAINT uk_sites_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_sites_one_default UNIQUE (tenant_id, default_slot),
    CONSTRAINT chk_sites_code CHECK (CHAR_LENGTH(TRIM(site_code)) > 0),
    CONSTRAINT chk_sites_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_sites_flags CHECK (enabled IN (0, 1) AND is_default IN (0, 1)),
    KEY idx_sites_tenant_enabled (tenant_id, enabled, id),
    CONSTRAINT fk_sites_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE warehouses (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT UNSIGNED NOT NULL,
    site_id BIGINT UNSIGNED NOT NULL,
    warehouse_code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    location VARCHAR(255) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    default_slot TINYINT GENERATED ALWAYS AS (CASE WHEN is_default = 1 THEN 1 ELSE NULL END) STORED,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_warehouses_tenant_code UNIQUE (tenant_id, warehouse_code),
    CONSTRAINT uk_warehouses_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_warehouses_one_default UNIQUE (tenant_id, default_slot),
    CONSTRAINT chk_warehouses_code CHECK (CHAR_LENGTH(TRIM(warehouse_code)) > 0),
    CONSTRAINT chk_warehouses_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_warehouses_flags CHECK (enabled IN (0, 1) AND is_default IN (0, 1)),
    KEY idx_warehouses_tenant_site (tenant_id, site_id),
    KEY idx_warehouses_tenant_enabled (tenant_id, enabled, id),
    CONSTRAINT fk_warehouses_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_warehouses_site FOREIGN KEY (tenant_id, site_id) REFERENCES sites (tenant_id, id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
