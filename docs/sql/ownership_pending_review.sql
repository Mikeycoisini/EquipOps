-- 只读核对清单。先确认 V3 已执行，再在目标 schema 中运行。
SELECT DATABASE() AS current_schema, VERSION() AS mysql_version;
SHOW CREATE TABLE equipment;
SHOW CREATE TABLE work_orders;
SHOW CREATE TABLE spare_parts;
SHOW CREATE TABLE inventory_transactions;

-- 供人工确定合法的目标企业、厂区和仓库；不自动选择最小 ID。
SELECT id, tenant_code, name, enabled FROM tenants ORDER BY id;
SELECT id, tenant_id, site_code, name, enabled, is_default FROM sites ORDER BY tenant_id, id;
SELECT id, tenant_id, site_id, warehouse_code, name, enabled, is_default FROM warehouses ORDER BY tenant_id, id;

SELECT id, asset_code, name, tenant_id, site_id FROM equipment
WHERE tenant_id IS NULL OR site_id IS NULL ORDER BY id;
SELECT w.id, w.order_no, w.equipment_id, w.tenant_id, w.site_id,
       e.tenant_id AS equipment_tenant_id, e.site_id AS equipment_site_id
FROM work_orders w JOIN equipment e ON e.id = w.equipment_id
WHERE w.tenant_id IS NULL OR w.site_id IS NULL OR NOT (w.tenant_id <=> e.tenant_id) OR NOT (w.site_id <=> e.site_id)
ORDER BY w.id;
SELECT id, part_code, name, stock_quantity, tenant_id FROM spare_parts WHERE tenant_id IS NULL ORDER BY id;
SELECT id, transaction_no, spare_part_id, work_order_id, tenant_id, warehouse_id
FROM inventory_transactions WHERE tenant_id IS NULL OR warehouse_id IS NULL ORDER BY id;

-- 核对日志/用料是否有父记录。它们通过工单归属受保护，不单独猜测 tenant_id。
SELECT l.id FROM work_order_logs l LEFT JOIN work_orders w ON w.id = l.work_order_id WHERE w.id IS NULL;
SELECT p.id FROM work_order_parts p
JOIN work_orders w ON w.id = p.work_order_id JOIN spare_parts s ON s.id = p.spare_part_id
WHERE w.tenant_id IS NOT NULL AND s.tenant_id IS NOT NULL AND w.tenant_id <> s.tenant_id;
