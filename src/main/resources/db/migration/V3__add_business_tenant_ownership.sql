-- 企业业务归属扩展。旧数据保留，不猜测归属；NULL 归属不会被业务 API 查询到。
-- 部署前停写并备份；V1/V2 已执行文件不修改。MySQL DDL 会隐式提交，不能依赖事务回滚。
-- 此迁移仅扩展结构；核对和回填脚本位于 docs/sql，不在应用启动时自动执行。

ALTER TABLE equipment
    ADD COLUMN tenant_id BIGINT UNSIGNED NULL COMMENT '所属企业，旧数据待核对',
    ADD COLUMN site_id BIGINT UNSIGNED NULL COMMENT '所属厂区，旧数据待核对',
    ADD UNIQUE KEY uk_equipment_tenant_id (tenant_id, id),
    ADD UNIQUE KEY uk_equipment_tenant_site_id (tenant_id, site_id, id),
    ADD UNIQUE KEY uk_equipment_tenant_code (tenant_id, asset_code),
    ADD KEY idx_equipment_tenant_status_id (tenant_id, status, id),
    ADD CONSTRAINT chk_equipment_ownership_pair CHECK
        ((tenant_id IS NULL AND site_id IS NULL) OR (tenant_id IS NOT NULL AND site_id IS NOT NULL)),
    ADD CONSTRAINT fk_equipment_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_equipment_tenant_site FOREIGN KEY (tenant_id, site_id) REFERENCES sites (tenant_id, id) ON DELETE RESTRICT;
-- 用企业内唯一约束替代全局唯一约束，不删除任何业务记录。
ALTER TABLE equipment DROP INDEX uk_equipment_asset_code;

ALTER TABLE work_orders
    ADD COLUMN tenant_id BIGINT UNSIGNED NULL COMMENT '所属企业，继承关联设备',
    ADD COLUMN site_id BIGINT UNSIGNED NULL COMMENT '所属厂区，继承关联设备',
    ADD UNIQUE KEY uk_work_orders_tenant_id (tenant_id, id),
    ADD KEY idx_work_orders_tenant_status_id (tenant_id, status, id),
    ADD KEY idx_work_orders_tenant_equipment_id (tenant_id, equipment_id, id),
    ADD KEY idx_work_orders_tenant_assignee (tenant_id, assignee_user_id, status, id),
    ADD CONSTRAINT chk_work_orders_ownership_pair CHECK
        ((tenant_id IS NULL AND site_id IS NULL) OR (tenant_id IS NOT NULL AND site_id IS NOT NULL)),
    ADD CONSTRAINT fk_work_orders_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_orders_owned_equipment FOREIGN KEY (tenant_id, site_id, equipment_id)
        REFERENCES equipment (tenant_id, site_id, id) ON DELETE RESTRICT;

ALTER TABLE spare_parts
    ADD COLUMN tenant_id BIGINT UNSIGNED NULL COMMENT '所属企业，旧数据待核对',
    ADD UNIQUE KEY uk_spare_parts_tenant_id (tenant_id, id),
    ADD UNIQUE KEY uk_spare_parts_tenant_code (tenant_id, part_code),
    ADD KEY idx_spare_parts_tenant_enabled_id (tenant_id, enabled, id),
    ADD CONSTRAINT fk_spare_parts_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT;
ALTER TABLE spare_parts DROP INDEX uk_spare_parts_code;

ALTER TABLE inventory_transactions
    ADD COLUMN tenant_id BIGINT UNSIGNED NULL COMMENT '流水所属企业，旧数据待核对',
    ADD COLUMN warehouse_id BIGINT UNSIGNED NULL COMMENT '流水所属仓库，旧数据待核对',
    ADD KEY idx_inventory_tenant_time (tenant_id, created_at, id),
    ADD KEY idx_inventory_tenant_part_time (tenant_id, spare_part_id, created_at),
    ADD KEY idx_inventory_tenant_warehouse_time (tenant_id, warehouse_id, created_at),
    ADD CONSTRAINT fk_inventory_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_inventory_owned_part FOREIGN KEY (tenant_id, spare_part_id) REFERENCES spare_parts (tenant_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_inventory_owned_warehouse FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses (tenant_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_inventory_owned_work_order FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders (tenant_id, id) ON DELETE RESTRICT;
