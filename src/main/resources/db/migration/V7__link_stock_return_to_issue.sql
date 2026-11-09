-- 退料关联原领料；旧流水保持NULL，不推测或回填历史来源。
-- 本文件仅扩展结构；部署前备份并核对待执行迁移，MySQL DDL不能依赖事务回滚。
-- 不在CHECK中引用work_order_id，兼容V1该列的ON DELETE SET NULL；业务层检查原工单完整性。
ALTER TABLE inventory_transactions
    ADD COLUMN issue_transaction_id BIGINT UNSIGNED NULL COMMENT '退料关联的原工单领料流水ID',
    ADD UNIQUE KEY uk_inventory_tenant_id (tenant_id, id),
    ADD KEY idx_inventory_tenant_issue (tenant_id, issue_transaction_id),
    ADD CONSTRAINT chk_inventory_return_source CHECK (
        issue_transaction_id IS NULL OR (
            tenant_id IS NOT NULL AND warehouse_id IS NOT NULL
            AND transaction_type = 'INBOUND' AND reference_type = 'WORK_ORDER'
        )
    );

-- 自引用外键须在被引用的(tenant_id,id)索引已创建后单独建立。
-- 不与上面的索引放进同一条ALTER，避免MySQL将旧表作为父表而报1215。
ALTER TABLE inventory_transactions
    ADD CONSTRAINT fk_inventory_return_issue FOREIGN KEY (tenant_id, issue_transaction_id)
        REFERENCES inventory_transactions (tenant_id, id) ON DELETE RESTRICT;
