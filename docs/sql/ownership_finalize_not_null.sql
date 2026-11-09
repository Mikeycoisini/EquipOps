-- 仅供人工审阅后执行，不在 Flyway 自动扫描目录中。
-- 前提：归属清单全部核对完成、没有未归属记录，业务已停写并备份。
-- 先运行下面计数，四项均必须为0。未确认前不要执行后面的 ALTER。
SELECT 'equipment' AS entity, COUNT(*) AS pending FROM equipment WHERE tenant_id IS NULL OR site_id IS NULL
UNION ALL SELECT 'work_orders', COUNT(*) FROM work_orders WHERE tenant_id IS NULL OR site_id IS NULL
UNION ALL SELECT 'spare_parts', COUNT(*) FROM spare_parts WHERE tenant_id IS NULL
UNION ALL SELECT 'inventory_transactions', COUNT(*) FROM inventory_transactions WHERE tenant_id IS NULL OR warehouse_id IS NULL;

-- 强制严格模式，避免非严格模式下把遗留 NULL 强制转换成默认值。
SET @ownership_previous_sql_mode = @@SESSION.sql_mode;
SET SESSION sql_mode = CONCAT_WS(',', @@SESSION.sql_mode, 'STRICT_ALL_TABLES');

-- 只有计数全部为0时，才逐句执行。MySQL DDL 隐式提交，不能用 ROLLBACK 撤销 DDL。
ALTER TABLE equipment MODIFY tenant_id BIGINT UNSIGNED NOT NULL, MODIFY site_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE work_orders MODIFY tenant_id BIGINT UNSIGNED NOT NULL, MODIFY site_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE spare_parts MODIFY tenant_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE inventory_transactions MODIFY tenant_id BIGINT UNSIGNED NOT NULL, MODIFY warehouse_id BIGINT UNSIGNED NOT NULL;

-- 正式推进下个版本前，把核对完成后的非空收紧固化为新的 Flyway 迁移；不要修改 V1/V2/V3。

SET SESSION sql_mode = @ownership_previous_sql_mode;
