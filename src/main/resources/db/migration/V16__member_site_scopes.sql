-- A3显式成员厂区范围：历史普通成员默认无授权，管理员仍由实时角色判断全企业范围。
-- 不将旧成员自动扩大到全部厂区；上线前由管理员依据已核对映射显式分配。
ALTER TABLE tenant_memberships ADD COLUMN site_scope_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_member_site_scope_version CHECK (site_scope_version >= 0);

CREATE TABLE tenant_member_site_scopes (
 tenant_id BIGINT UNSIGNED NOT NULL,
 membership_id BIGINT UNSIGNED NOT NULL,
 site_id BIGINT UNSIGNED NOT NULL,
 granted_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY (tenant_id,membership_id,site_id),
 CONSTRAINT fk_member_site_scope_member FOREIGN KEY (tenant_id,membership_id)
   REFERENCES tenant_memberships(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_site_scope_site FOREIGN KEY (tenant_id,site_id)
   REFERENCES sites(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_site_scope_actor FOREIGN KEY (granted_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 每个任务保存不可变范围；无快照的历史任务须重新导出，不推测旧文件的数据范围。
CREATE TABLE inventory_export_task_sites (
 task_id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 site_id BIGINT UNSIGNED NOT NULL,
 PRIMARY KEY(task_id,site_id),
 KEY idx_export_task_scope_tenant(tenant_id,site_id),
 CONSTRAINT fk_export_task_scope_task FOREIGN KEY(task_id) REFERENCES inventory_export_tasks(id) ON DELETE RESTRICT,
 CONSTRAINT fk_export_task_scope_site FOREIGN KEY(tenant_id,site_id) REFERENCES sites(tenant_id,id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
