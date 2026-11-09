-- 具体设备与备件适配关系；仅新增结构，不改已执行的V1～V9。
-- 备件停用不会删除关系。物理删除设备/备件时仅清理附属适配关系，保持原CRUD行为。
-- 部署前核对目标库及备份；本轮不执行本迁移。
CREATE TABLE equipment_spare_parts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '适配关系主键',
    tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属企业',
    equipment_id BIGINT UNSIGNED NOT NULL COMMENT '具体设备ID',
    spare_part_id BIGINT UNSIGNED NOT NULL COMMENT '适用备件ID',
    created_by BIGINT UNSIGNED NULL COMMENT '创建人，账号物理删除时置空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '关系建立时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_equipment_spare_parts_pair UNIQUE (tenant_id, equipment_id, spare_part_id),
    KEY idx_equipment_spare_parts_reverse (tenant_id, spare_part_id, equipment_id),
    KEY idx_equipment_spare_parts_creator (created_by),
    CONSTRAINT fk_equipment_spare_parts_equipment FOREIGN KEY (tenant_id, equipment_id)
        REFERENCES equipment (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_equipment_spare_parts_part FOREIGN KEY (tenant_id, spare_part_id)
        REFERENCES spare_parts (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_equipment_spare_parts_creator FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
