-- 新增仓库库存余额；不修改 V1..V4，不搬运或删除任何旧业务数据。
-- 旧 spare_parts.stock_quantity/min_stock_quantity 的归属核对仍由人工决定。
-- 本文件只创建结构；部署前核对目标 schema、备份与 Flyway 历史，MySQL DDL 会隐式提交。
CREATE TABLE warehouse_stock (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '库存记录主键',
    tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属企业',
    warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '所属仓库',
    spare_part_id BIGINT UNSIGNED NOT NULL COMMENT '关联备件',
    quantity DECIMAL(14,3) UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前库存数量',
    min_stock_quantity DECIMAL(14,3) UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前仓库安全库存',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建账时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后变动时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_warehouse_stock_tenant_warehouse_part UNIQUE (tenant_id, warehouse_id, spare_part_id),
    KEY idx_warehouse_stock_tenant_part (tenant_id, spare_part_id, warehouse_id),
    CONSTRAINT chk_warehouse_stock_quantity CHECK (quantity >= 0),
    CONSTRAINT chk_warehouse_stock_min_quantity CHECK (min_stock_quantity >= 0),
    CONSTRAINT fk_warehouse_stock_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_warehouse_stock_warehouse FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES warehouses (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_warehouse_stock_part FOREIGN KEY (tenant_id, spare_part_id)
        REFERENCES spare_parts (tenant_id, id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
