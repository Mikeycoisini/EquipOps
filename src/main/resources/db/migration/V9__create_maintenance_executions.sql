-- 执行结果记录及历史快照；只新增结构，不修改V1～V8，不回填历史数据。
-- 应用启动时Flyway会执行；部署前须核对目标库和备份。本轮不执行迁移。
ALTER TABLE maintenance_plans ADD UNIQUE KEY uk_plans_tenant_id (tenant_id, id);

CREATE TABLE maintenance_executions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '执行记录主键',
    tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属企业',
    plan_id BIGINT UNSIGNED NOT NULL COMMENT '关联计划主键',
    site_id BIGINT UNSIGNED NOT NULL COMMENT '本期设备所属厂区快照',
    equipment_id BIGINT UNSIGNED NOT NULL COMMENT '本期关联设备ID快照',
    request_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '幂等请求号，同一企业内唯一',
    plan_name VARCHAR(160) NOT NULL COMMENT '登记时计划名称快照',
    plan_type VARCHAR(20) NOT NULL COMMENT '登记时计划类型快照',
    plan_content VARCHAR(2000) NOT NULL COMMENT '登记时执行内容快照',
    cycle_days INT NOT NULL COMMENT '登记时周期天数快照',
    scheduled_date DATE NOT NULL COMMENT '本期原定到期日期',
    executed_at DATETIME(3) NOT NULL COMMENT '服务端记录的上海时区实际完成时间',
    executed_by BIGINT UNSIGNED NOT NULL COMMENT '实际执行人用户ID',
    result VARCHAR(2000) NOT NULL COMMENT '本次执行结果说明',
    next_due_date DATE NOT NULL COMMENT '本次完成后推算的下次到期日',
    equipment_asset_code VARCHAR(64) NOT NULL COMMENT '登记时设备编号快照',
    equipment_name VARCHAR(160) NOT NULL COMMENT '登记时设备名称快照',
    executed_by_name VARCHAR(100) NOT NULL COMMENT '登记时执行人姓名快照',
    PRIMARY KEY (id),
    CONSTRAINT uk_executions_request UNIQUE (tenant_id, request_id),
    CONSTRAINT uk_executions_period UNIQUE (tenant_id, plan_id, scheduled_date),
    KEY idx_executions_tenant_equipment (tenant_id, equipment_id, executed_at),
    KEY idx_executions_tenant_site (tenant_id, site_id),
    KEY idx_executions_actor (executed_by),
    CONSTRAINT chk_executions_result CHECK (CHAR_LENGTH(TRIM(result)) > 0),
    CONSTRAINT chk_executions_cycle CHECK (cycle_days BETWEEN 1 AND 3650),
    CONSTRAINT chk_executions_type CHECK (plan_type IN ('MAINTENANCE', 'INSPECTION')),
    CONSTRAINT chk_executions_next_due CHECK (next_due_date > scheduled_date),
    CONSTRAINT fk_executions_plan FOREIGN KEY (tenant_id, plan_id) REFERENCES maintenance_plans (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_executions_equipment FOREIGN KEY (tenant_id, equipment_id) REFERENCES equipment (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_executions_site FOREIGN KEY (tenant_id, site_id) REFERENCES sites (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_executions_actor FOREIGN KEY (executed_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
