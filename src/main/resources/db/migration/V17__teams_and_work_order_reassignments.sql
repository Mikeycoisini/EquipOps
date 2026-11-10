-- A4 团队资格只收窄个人厂区权限，不为历史成员自动授予团队或厂区。
CREATE TABLE teams (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 tenant_id BIGINT UNSIGNED NOT NULL,
 team_code VARCHAR(64) NOT NULL,
 name VARCHAR(160) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 0,
 created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),
 UNIQUE KEY uk_teams_tenant_id(tenant_id,id),
 UNIQUE KEY uk_teams_tenant_code(tenant_id,team_code),
 KEY idx_teams_tenant_enabled(tenant_id,enabled,id),
 CONSTRAINT chk_teams_code CHECK(CHAR_LENGTH(TRIM(team_code))>0),
 CONSTRAINT chk_teams_name CHECK(CHAR_LENGTH(TRIM(name))>0),
 CONSTRAINT chk_teams_enabled CHECK(enabled IN(0,1)),
 CONSTRAINT chk_teams_version CHECK(version>=0),
 CONSTRAINT fk_teams_tenant FOREIGN KEY(tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
 CONSTRAINT fk_teams_creator FOREIGN KEY(created_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_members (
 tenant_id BIGINT UNSIGNED NOT NULL,
 team_id BIGINT UNSIGNED NOT NULL,
 membership_id BIGINT UNSIGNED NOT NULL,
 PRIMARY KEY(tenant_id,team_id,membership_id),
 KEY idx_team_members_membership(tenant_id,membership_id,team_id),
 CONSTRAINT fk_team_members_team FOREIGN KEY(tenant_id,team_id) REFERENCES teams(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT fk_team_members_membership FOREIGN KEY(tenant_id,membership_id) REFERENCES tenant_memberships(tenant_id,id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_site_scopes (
 tenant_id BIGINT UNSIGNED NOT NULL,
 team_id BIGINT UNSIGNED NOT NULL,
 site_id BIGINT UNSIGNED NOT NULL,
 PRIMARY KEY(tenant_id,team_id,site_id),
 KEY idx_team_site_scopes_site(tenant_id,site_id,team_id),
 CONSTRAINT fk_team_site_scopes_team FOREIGN KEY(tenant_id,team_id) REFERENCES teams(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT fk_team_site_scopes_site FOREIGN KEY(tenant_id,site_id) REFERENCES sites(tenant_id,id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_operations (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 tenant_id BIGINT UNSIGNED NOT NULL,
 team_id BIGINT UNSIGNED NOT NULL,
 request_id CHAR(36) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 action VARCHAR(32) NOT NULL,
 result_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),
 UNIQUE KEY uk_team_operations_request(tenant_id,request_id),
 KEY idx_team_operations_team(tenant_id,team_id,id),
 CONSTRAINT fk_team_operations_team FOREIGN KEY(tenant_id,team_id) REFERENCES teams(tenant_id,id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE work_orders
 ADD COLUMN team_id BIGINT UNSIGNED NULL,
 ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
 ADD KEY idx_work_orders_tenant_team_status(tenant_id,team_id,status,id),
 ADD CONSTRAINT chk_work_orders_version CHECK(version>=0),
 ADD CONSTRAINT chk_work_orders_team_ownership CHECK(team_id IS NULL OR tenant_id IS NOT NULL),
 ADD CONSTRAINT fk_work_orders_team FOREIGN KEY(tenant_id,team_id) REFERENCES teams(tenant_id,id) ON DELETE RESTRICT;

CREATE TABLE work_order_reassignments (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 tenant_id BIGINT UNSIGNED NOT NULL,
 work_order_id BIGINT UNSIGNED NOT NULL,
 request_id CHAR(36) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 before_assignee_user_id BIGINT UNSIGNED NULL,
 after_assignee_user_id BIGINT UNSIGNED NOT NULL,
 before_team_id BIGINT UNSIGNED NULL,
 after_team_id BIGINT UNSIGNED NULL,
 actor_user_id BIGINT UNSIGNED NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 result_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),
 UNIQUE KEY uk_work_order_reassignments_request(tenant_id,request_id),
 KEY idx_work_order_reassignments_order(tenant_id,work_order_id,id),
 CONSTRAINT chk_work_order_reassignments_reason CHECK(CHAR_LENGTH(TRIM(reason))>0),
 CONSTRAINT fk_reassignments_order FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_orders(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT fk_reassignments_before_user FOREIGN KEY(before_assignee_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_reassignments_after_user FOREIGN KEY(after_assignee_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_reassignments_actor FOREIGN KEY(actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_reassignments_before_team FOREIGN KEY(tenant_id,before_team_id) REFERENCES teams(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT fk_reassignments_after_team FOREIGN KEY(tenant_id,after_team_id) REFERENCES teams(tenant_id,id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
