-- 历史及人工报修工单默认保留原统计口径。
ALTER TABLE work_orders
    ADD COLUMN source VARCHAR(24) NOT NULL DEFAULT 'REPAIR_REPORT',
    ADD CONSTRAINT chk_work_order_source CHECK (source IN ('REPAIR_REPORT','RUNTIME_MAINTENANCE'));

-- 每台设备保留下一次累计小时阈值及该阈值生成的工单。
CREATE TABLE equipment_runtime_maintenance (
    tenant_id BIGINT UNSIGNED NOT NULL,
    site_id BIGINT UNSIGNED NOT NULL,
    equipment_id BIGINT UNSIGNED NOT NULL,
    threshold_hours DECIMAL(14,2) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    work_order_id BIGINT UNSIGNED NULL,
    PRIMARY KEY (tenant_id, site_id, equipment_id),
    CONSTRAINT chk_runtime_maintenance_threshold CHECK (threshold_hours > 0),
    CONSTRAINT fk_runtime_maintenance_equipment FOREIGN KEY (tenant_id, site_id, equipment_id)
        REFERENCES equipment (tenant_id, site_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_runtime_maintenance_order FOREIGN KEY (tenant_id, work_order_id)
        REFERENCES work_orders (tenant_id, id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
