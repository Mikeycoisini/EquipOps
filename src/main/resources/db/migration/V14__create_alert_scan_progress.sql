-- 告警批次续扫位置及规则轮询公平性，不修改V13和原业务表。
CREATE TABLE alert_scan_progress (
 tenant_id BIGINT UNSIGNED NOT NULL,
 rule_id BIGINT UNSIGNED NOT NULL,
 rule_version BIGINT NOT NULL,
 window_start DATETIME(6) NOT NULL,
 window_end DATETIME(6) NOT NULL,
 last_finished_at DATETIME(6) NULL,
 last_task_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 completed BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,rule_id),
 KEY idx_alert_scan_fairness(updated_at,rule_id),
 CONSTRAINT fk_alert_scan_rule FOREIGN KEY(tenant_id,rule_id) REFERENCES alert_rules(tenant_id,id),
 CONSTRAINT chk_alert_scan_window CHECK(window_start < window_end),
 CONSTRAINT chk_alert_scan_version CHECK(version>=0 AND rule_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
