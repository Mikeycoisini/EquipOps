-- M2告警表结构草案：MySQL 8.0.16+，尚未执行，不是Flyway迁移。
-- 不含USE、DROP、数据初始化或现有表修改；正式执行前核对目标及远程M1迁移版本。
-- 新表使用同企业复合外键；对已有用户/成员/导出任务由服务实时检查，避免修改旧表。
CREATE TABLE alert_rules (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 tenant_id BIGINT UNSIGNED NOT NULL,
 rule_code VARCHAR(64) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 threshold_count INT NOT NULL,
 window_seconds INT NOT NULL,
 cooldown_seconds INT NOT NULL,
 owner_user_id BIGINT UNSIGNED NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_alert_rule_code(tenant_id,rule_code),
 UNIQUE KEY uk_alert_rule_tenant_id(tenant_id,id),
 CONSTRAINT chk_alert_rule_code CHECK(rule_code='EXPORT_TASK_SYSTEM_FAILURE'),
 CONSTRAINT chk_alert_rule_threshold CHECK(threshold_count>0),
 CONSTRAINT chk_alert_rule_window CHECK(window_seconds>0),
 CONSTRAINT chk_alert_rule_cooldown CHECK(cooldown_seconds>=0),
 CONSTRAINT chk_alert_rule_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE alert_events (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 tenant_id BIGINT UNSIGNED NOT NULL,
 rule_id BIGINT UNSIGNED NOT NULL,
 fingerprint VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
 recovery_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
 open_slot TINYINT NULL DEFAULT 1,
 occurrence_count BIGINT NOT NULL DEFAULT 0,
 first_task_id BIGINT UNSIGNED NOT NULL,
 latest_task_id BIGINT UNSIGNED NOT NULL,
 owner_user_id BIGINT UNSIGNED NOT NULL,
 first_seen_at DATETIME(6) NOT NULL,
 last_seen_at DATETIME(6) NOT NULL,
 acknowledged_at DATETIME(6) NULL,
 recovered_at DATETIME(6) NULL,
 resolved_at DATETIME(6) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_alert_event_tenant_id(tenant_id,id),
 UNIQUE KEY uk_alert_event_rule_id(tenant_id,rule_id,id),
 UNIQUE KEY uk_alert_active_type(tenant_id,rule_id,error_code,open_slot),
 KEY idx_alert_event_page(tenant_id,status,created_at,id),
 CONSTRAINT fk_alert_event_rule FOREIGN KEY(tenant_id,rule_id) REFERENCES alert_rules(tenant_id,id),
 CONSTRAINT chk_alert_event_status CHECK(status IN('OPEN','ACKNOWLEDGED','RESOLVED')),
 -- 使用IS NULL/IS NOT NULL避免CHECK遇到NULL产生UNKNOWN而放过非法活动槽。
 CONSTRAINT chk_alert_event_slot CHECK(
  (status IN('OPEN','ACKNOWLEDGED') AND open_slot IS NOT NULL AND open_slot=1 AND resolved_at IS NULL)
  OR (status='RESOLVED' AND open_slot IS NULL AND resolved_at IS NOT NULL)),
 CONSTRAINT chk_alert_recovery CHECK(
  (recovery_status='UNVERIFIED' AND recovered_at IS NULL)
  OR (recovery_status='RECOVERED' AND recovered_at IS NOT NULL)),
 CONSTRAINT chk_alert_event_count CHECK(occurrence_count>=0),
 CONSTRAINT chk_alert_event_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE alert_actions (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 tenant_id BIGINT UNSIGNED NOT NULL,
 event_id BIGINT UNSIGNED NOT NULL,
 actor_user_id BIGINT UNSIGNED NOT NULL,
 action_type VARCHAR(32) NOT NULL,
 note VARCHAR(1000) NOT NULL,
 request_id VARCHAR(36) NULL COMMENT '追踪编号，不是动作幂等键',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), KEY idx_alert_action_event(tenant_id,event_id,id),
 CONSTRAINT fk_alert_action_event FOREIGN KEY(tenant_id,event_id) REFERENCES alert_events(tenant_id,id),
 CONSTRAINT chk_alert_action_type CHECK(action_type IN('ACKNOWLEDGE','ADD_NOTE','RESOLVE')),
 CONSTRAINT chk_alert_action_note CHECK(CHAR_LENGTH(TRIM(note))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE alert_notifications (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 tenant_id BIGINT UNSIGNED NOT NULL,
 event_id BIGINT UNSIGNED NOT NULL,
 recipient_user_id BIGINT UNSIGNED NOT NULL,
 delivery_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
 title VARCHAR(150) NOT NULL,
 message VARCHAR(500) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NULL,
 safe_error_code VARCHAR(64) NULL,
 delivered_at DATETIME(6) NULL,
 read_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_alert_delivery(tenant_id,delivery_key),
 KEY idx_alert_notification_recipient(tenant_id,recipient_user_id,created_at,id),
 KEY idx_alert_notification_retry(status,next_attempt_at,id),
 CONSTRAINT fk_alert_notification_event FOREIGN KEY(tenant_id,event_id) REFERENCES alert_events(tenant_id,id),
 CONSTRAINT chk_alert_notification_status CHECK(status IN('PENDING','DELIVERED','FAILED')),
 CONSTRAINT chk_alert_notification_attempts CHECK(attempts>=0),
 CONSTRAINT chk_alert_notification_delivery CHECK(status<>'DELIVERED' OR delivered_at IS NOT NULL),
 CONSTRAINT chk_alert_notification_read CHECK(read_at IS NULL OR (status='DELIVERED' AND delivered_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 补充证据表：同一规则的同一失败任务只计入一次；未达阈值也可先保存证据。
-- 不能只靠latest_task_id，任务完成次序不等于ID次序；多实例可能反复扫描同一任务。
CREATE TABLE alert_event_sources (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 tenant_id BIGINT UNSIGNED NOT NULL,
 rule_id BIGINT UNSIGNED NOT NULL,
 event_id BIGINT UNSIGNED NULL,
 task_id BIGINT UNSIGNED NOT NULL,
 error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 task_finished_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_alert_source_task(tenant_id,rule_id,task_id),
 KEY idx_alert_source_window(tenant_id,rule_id,error_code,task_finished_at,id),
 KEY idx_alert_source_event(tenant_id,rule_id,event_id),
 CONSTRAINT fk_alert_source_rule FOREIGN KEY(tenant_id,rule_id) REFERENCES alert_rules(tenant_id,id),
 CONSTRAINT fk_alert_source_event FOREIGN KEY(tenant_id,rule_id,event_id) REFERENCES alert_events(tenant_id,rule_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
