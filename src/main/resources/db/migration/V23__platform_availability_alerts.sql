CREATE TABLE platform_probe_cursor (
    id INT PRIMARY KEY CHECK (id = 1),
    stream_id CHAR(36) NULL,
    last_event_id BIGINT NOT NULL CHECK (last_event_id >= 0),
    last_sample_at DATETIME(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO platform_probe_cursor (id, last_event_id) VALUES (1, 0);

CREATE TABLE platform_availability_alerts (
    id BIGINT NOT NULL PRIMARY KEY,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    evidence VARCHAR(32) NOT NULL,
    opened_at DATETIME(3) NOT NULL,
    recovered_at DATETIME(3) NULL,
    notified_at DATETIME(3) NULL,
    read_at DATETIME(3) NULL,
    acknowledged_at DATETIME(3) NULL,
    resolved_at DATETIME(3) NULL,
    INDEX idx_platform_alert_inbox (owner_user_id, notified_at, id),
    FOREIGN KEY (owner_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_availability_actions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('ACKNOWLEDGE','RESOLVE')),
    occurred_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_platform_availability_action (alert_id, action),
    FOREIGN KEY (alert_id) REFERENCES platform_availability_alerts(id),
    FOREIGN KEY (actor_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
