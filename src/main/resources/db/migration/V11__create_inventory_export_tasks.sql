-- 库存流水Excel异步导出任务；仅新增表，不修改已执行的V1～V10。
-- 使用Flyway当前连接的数据库，不写死equipops或equipops_test库名。
-- 已手动创建同名表的环境，须先核对结构及迁移历史，不用IF NOT EXISTS掩盖结构差异。
-- 保持已联调结构：27个字段、主键、4个辅助索引及8种状态约束。
-- 任务归属由Service/Mapper校验；本迁移不额外引入关联对象删除的外键规则。
CREATE TABLE inventory_export_tasks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '导出任务ID',
    tenant_id BIGINT UNSIGNED NOT NULL COMMENT '可信企业ID',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '可信创建人ID',
    warehouse_id BIGINT UNSIGNED NULL COMMENT '筛选仓库，可为空',
    spare_part_id BIGINT UNSIGNED NULL COMMENT '筛选备件，可为空',
    work_order_id BIGINT UNSIGNED NULL COMMENT '筛选工单，可为空',
    transaction_type VARCHAR(32) NULL COMMENT '筛选INBOUND/OUTBOUND/ADJUSTMENT',
    reference_type VARCHAR(32) NULL COMMENT '筛选WORK_ORDER/PURCHASE_ORDER/MANUAL/OTHER',
    start_time DATETIME(6) NULL COMMENT '筛选开始时间，包含',
    end_time DATETIME(6) NULL COMMENT '筛选结束时间，包含',
    upper_bound_id BIGINT UNSIGNED NULL COMMENT '开始执行时固定的流水ID上界',
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED' COMMENT '任务状态',
    processed_rows BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已写数据行数，不含表头',
    storage_key VARCHAR(255) NULL COMMENT '受控根目录下的相对存储键，仅服务端使用',
    file_name VARCHAR(255) NULL COMMENT '下载展示文件名',
    file_size BIGINT UNSIGNED NULL COMMENT '最终文件字节数',
    error_code VARCHAR(64) NULL COMMENT '可公开错误编码',
    error_message VARCHAR(500) NULL COMMENT '可公开错误原因',
    owner_instance_id VARCHAR(64) NOT NULL COMMENT '执行实例UUID',
    run_token VARCHAR(64) NOT NULL COMMENT '本次执行UUID令牌',
    lease_until DATETIME(6) NOT NULL COMMENT '数据库时钟租约截止时间',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL COMMENT '实际开始时间',
    finished_at DATETIME(6) NULL COMMENT '终态时间',
    deadline_at DATETIME(6) NULL COMMENT '运行5分钟截止时间，不随续租延长',
    expires_at DATETIME(6) NULL COMMENT '成功文件下载有效期',
    PRIMARY KEY (id),
    KEY idx_export_owner_active (tenant_id, created_by, status, id),
    KEY idx_export_status_lease (status, lease_until),
    KEY idx_export_status_deadline (status, deadline_at),
    KEY idx_export_status_expiry (status, expires_at),
    CONSTRAINT chk_export_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'SUCCEEDED',
        'FAILED', 'CANCELLED', 'REJECTED', 'EXPIRED'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
