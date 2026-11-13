-- 记录现场抄表结果，设备档案中的手工字段保留原有用途。
CREATE TABLE equipment_runtime_readings (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    site_id BIGINT UNSIGNED NOT NULL,
    equipment_id BIGINT UNSIGNED NOT NULL,
    hours DECIMAL(14,2) NOT NULL,
    recorded_by BIGINT UNSIGNED NOT NULL,
    recorded_at BIGINT NOT NULL COMMENT 'UTC Unix毫秒时间戳',
    KEY idx_runtime_reading_equipment (tenant_id, site_id, equipment_id, id),
    CONSTRAINT fk_runtime_reading_equipment FOREIGN KEY (tenant_id, site_id, equipment_id)
        REFERENCES equipment (tenant_id, site_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_runtime_reading_member FOREIGN KEY (tenant_id, recorded_by)
        REFERENCES tenant_memberships (tenant_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT chk_runtime_reading_hours CHECK (hours >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
