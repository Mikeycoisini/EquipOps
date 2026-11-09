-- 日期周期维护/巡检计划。仅新增结构；V1～V7不修改，不回填或更新设备档案。
-- 应用下次启动时Flyway会应用本迁移；部署前须核对目标库及备份。
CREATE TABLE maintenance_plans (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '计划主键',
    tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属企业',
    site_id BIGINT UNSIGNED NOT NULL COMMENT '继承设备所属厂区',
    equipment_id BIGINT UNSIGNED NOT NULL COMMENT '关联设备',
    name VARCHAR(160) NOT NULL COMMENT '计划名称',
    plan_type VARCHAR(20) NOT NULL COMMENT 'MAINTENANCE维护，INSPECTION巡检',
    content VARCHAR(2000) NOT NULL COMMENT '执行内容',
    cycle_days INT NOT NULL COMMENT '日期周期天数，1至3650',
    next_due_date DATE NOT NULL COMMENT '下次到期日，创建时为首次到期日',
    assignee_user_id BIGINT UNSIGNED NOT NULL COMMENT '负责人用户ID',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_plans_tenant_due (tenant_id, enabled, next_due_date, id),
    KEY idx_plans_tenant_equipment (tenant_id, site_id, equipment_id),
    KEY idx_plans_tenant_assignee (tenant_id, assignee_user_id),
    KEY idx_plans_creator (created_by),
    CONSTRAINT chk_plans_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_plans_content CHECK (CHAR_LENGTH(TRIM(content)) > 0),
    CONSTRAINT chk_plans_type CHECK (plan_type IN ('MAINTENANCE', 'INSPECTION')),
    CONSTRAINT chk_plans_cycle CHECK (cycle_days BETWEEN 1 AND 3650),
    CONSTRAINT chk_plans_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT fk_plans_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_plans_equipment FOREIGN KEY (tenant_id, site_id, equipment_id)
        REFERENCES equipment (tenant_id, site_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_plans_assignee FOREIGN KEY (tenant_id, assignee_user_id)
        REFERENCES tenant_memberships (tenant_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_plans_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
