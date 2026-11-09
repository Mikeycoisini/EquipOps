-- 人工核对后填写映射；仅在停写、备份后的隔离副本先执行。
-- 本脚本不含真实 ID，不会默认把旧数据分配给任何企业。
-- 所有操作须在同一个 MySQL 会话完成。临时表仅在该会话存在。
CREATE TEMPORARY TABLE reviewed_equipment_owner (
    equipment_id BIGINT UNSIGNED PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    site_id BIGINT UNSIGNED NOT NULL
);
CREATE TEMPORARY TABLE reviewed_part_owner (
    spare_part_id BIGINT UNSIGNED PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL
);
CREATE TEMPORARY TABLE reviewed_transaction_warehouse (
    transaction_id BIGINT UNSIGNED PRIMARY KEY,
    warehouse_id BIGINT UNSIGNED NOT NULL
);

-- 在此填写经人工核对的映射，例如每条 equipment_id 的真实 tenant_id、site_id。
-- INSERT INTO reviewed_equipment_owner VALUES (..., ..., ...);
-- INSERT INTO reviewed_part_owner VALUES (..., ...);
-- INSERT INTO reviewed_transaction_warehouse VALUES (..., ...);

-- 保留原有更新时间，迁移仅补归属，不伪造业务编辑时间。
START TRANSACTION;
-- 只回填尚未归属的记录，且目标必须是该企业的有效默认厂区。
UPDATE equipment e
JOIN reviewed_equipment_owner m ON m.equipment_id = e.id
JOIN tenants t ON t.id = m.tenant_id AND t.enabled = 1
JOIN sites s ON s.tenant_id = t.id AND s.id = m.site_id AND s.enabled = 1 AND s.is_default = 1
SET e.tenant_id = m.tenant_id, e.site_id = m.site_id, e.updated_at = e.updated_at
WHERE e.tenant_id IS NULL AND e.site_id IS NULL;

-- 工单只从已经确认归属的关联设备继承，不单独指定不同归属。
UPDATE work_orders w JOIN equipment e ON e.id = w.equipment_id
SET w.tenant_id = e.tenant_id, w.site_id = e.site_id, w.updated_at = w.updated_at
WHERE w.tenant_id IS NULL AND w.site_id IS NULL AND e.tenant_id IS NOT NULL AND e.site_id IS NOT NULL;

UPDATE spare_parts p
JOIN reviewed_part_owner m ON m.spare_part_id = p.id
JOIN tenants t ON t.id = m.tenant_id AND t.enabled = 1
SET p.tenant_id = m.tenant_id, p.updated_at = p.updated_at WHERE p.tenant_id IS NULL;

-- 流水必须先确定仓库，并与备件及可选工单企业一致，不能从操作人猜归属。
UPDATE inventory_transactions i
JOIN reviewed_transaction_warehouse m ON m.transaction_id = i.id
JOIN warehouses wh ON wh.id = m.warehouse_id
JOIN spare_parts p ON p.id = i.spare_part_id AND p.tenant_id = wh.tenant_id
LEFT JOIN work_orders w ON w.id = i.work_order_id
SET i.tenant_id = wh.tenant_id, i.warehouse_id = wh.id
WHERE i.tenant_id IS NULL AND i.warehouse_id IS NULL
  AND (i.work_order_id IS NULL OR w.tenant_id = wh.tenant_id);

-- 检查实际写入与映射是否一致；未匹配的映射不能当作已经回填成功。
SELECT m.* FROM reviewed_equipment_owner m LEFT JOIN equipment e ON e.id = m.equipment_id
WHERE e.id IS NULL OR NOT (e.tenant_id <=> m.tenant_id) OR NOT (e.site_id <=> m.site_id);
SELECT m.* FROM reviewed_part_owner m LEFT JOIN spare_parts p ON p.id = m.spare_part_id
WHERE p.id IS NULL OR NOT (p.tenant_id <=> m.tenant_id);
SELECT m.* FROM reviewed_transaction_warehouse m LEFT JOIN inventory_transactions i ON i.id = m.transaction_id
WHERE i.id IS NULL OR NOT (i.warehouse_id <=> m.warehouse_id);

-- 此处故意不自动提交：确认映射、记录数、审计字段及关联无误后，在同一会话手动 COMMIT。
-- 任意错误或未匹配映射，执行 ROLLBACK，修改映射后重做；不要使用 mysql --force 忽略错误。
