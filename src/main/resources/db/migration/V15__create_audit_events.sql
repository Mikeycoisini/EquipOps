-- 统一审计事件表；仅新增表，不修改已执行的V1～V12。
-- 使用Flyway当前连接的数据库，不写死equipops或equipops_test库名。
-- 审计只追加，不提供修改/删除；不加外键，避免用户或企业记录变化影响历史审计。
-- action/object_type使用VARCHAR，后续模块新增动作不需要改表；actor_type/outcome有固定取值集合。
CREATE TABLE audit_events (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '审计事件ID',
    tenant_id     BIGINT UNSIGNED NULL COMMENT '可信企业ID；平台事件为空',
    actor_user_id BIGINT UNSIGNED NULL COMMENT '操作者用户ID；系统任务为空',
    actor_type    VARCHAR(16) NOT NULL COMMENT 'USER/SYSTEM/PLATFORM',
    action        VARCHAR(64) NOT NULL COMMENT '动作编码，如MEMBER_ROLES_CHANGED',
    object_type   VARCHAR(64) NOT NULL COMMENT '对象类型，如MEMBER',
    object_id     BIGINT UNSIGNED NULL COMMENT '对象ID，如成员关系ID',
    outcome       VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILURE/DENIED',
    request_id    VARCHAR(36) NULL COMMENT '请求编号；同步请求由服务端生成',
    changes_json  JSON NULL COMMENT '脱敏后的变更摘要，不保存请求正文、密码或令牌',
    occurred_at   DATETIME(6) NOT NULL COMMENT '业务发生时间',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '落库时间',
    PRIMARY KEY (id),
    KEY idx_audit_tenant_time   (tenant_id, occurred_at, id),
    KEY idx_audit_tenant_object (tenant_id, object_type, object_id),
    KEY idx_audit_tenant_actor  (tenant_id, actor_user_id, occurred_at),
    CONSTRAINT chk_audit_actor_type CHECK (actor_type IN ('USER', 'SYSTEM', 'PLATFORM')),
    CONSTRAINT chk_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
