-- A9重建：只读工具调用审计与防重放。
CREATE TABLE ai_tool_invocations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    jti VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tool_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL,
    row_count INT NULL,
    duration_ms INT NULL,
    facts_json JSON NULL,
    warnings_json JSON NULL,
    as_of DATETIME(3) NULL,
    error_code VARCHAR(80) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_tool_replay (tenant_id,jti,tool_name),
    KEY idx_ai_tool_task (tenant_id,task_id),
    KEY idx_ai_tool_created (created_at),
    CONSTRAINT fk_ai_tool_task FOREIGN KEY (tenant_id,task_id) REFERENCES ai_tasks(tenant_id,id)
        ON DELETE CASCADE,
    CONSTRAINT fk_ai_tool_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_ai_tool_state CHECK (state IN ('RUNNING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
