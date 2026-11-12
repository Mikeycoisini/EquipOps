-- 保留 enabled 作为既有业务入口使用的唯一启停状态。
ALTER TABLE tenants ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE platform_operator_roles (
    user_id BIGINT UNSIGNED NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    granted_by BIGINT UNSIGNED NULL,
    granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, role_code),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (granted_by) REFERENCES users(id),
    CHECK (role_code = 'PLATFORM_OPERATOR'),
    CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_tenant_actions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    audit_event_id BIGINT UNSIGNED NOT NULL,
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    before_state VARCHAR(16) NULL,
    after_state VARCHAR(16) NOT NULL,
    lifecycle_version BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_platform_action_audit (audit_event_id),
    KEY idx_platform_tenant_action (tenant_id, id),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    FOREIGN KEY (actor_user_id) REFERENCES users(id),
    FOREIGN KEY (audit_event_id) REFERENCES audit_events(id),
    CHECK (action IN ('TENANT_OPENED','TENANT_SUSPENDED','TENANT_RESUMED')),
    CHECK (after_state IN ('ACTIVE','SUSPENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
