-- 本人导出任务按创建时间、ID倒序列表索引；保留原活动任务及租约等索引。
-- 不改任务数据、排序、COUNT或业务规则；表名使用Flyway当前连接的数据库。
-- 本轮仅授权迁移equipops_test；其他环境须另行确认执行范围。
ALTER TABLE inventory_export_tasks
    ADD INDEX idx_export_owner_created (tenant_id, created_by, created_at, id),
    ALGORITHM=INPLACE,
    LOCK=NONE;
