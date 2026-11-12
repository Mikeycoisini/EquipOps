package com.platform;

import com.auth.service.CurrentAccessService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PlatformTenantService {
    private static final String TENANT_COLUMNS = "id,tenant_code,name,enabled,lifecycle_version,created_at,updated_at";
    private final CurrentAccessService access;
    private final JdbcTemplate jdbc;

    public enum State { ACTIVE, SUSPENDED }
    private enum Event { TENANT_OPENED, TENANT_SUSPENDED, TENANT_RESUMED }

    public record Operator(String userId, String role) {}
    public record Tenant(String id, String tenantCode, String name, State state, long version,
                         LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Action(String id, String actorUserId, String action, String reason, State beforeState,
                         State afterState, long version, String requestId, LocalDateTime occurredAt) {}

    @Transactional(readOnly = true)
    public Operator operator() {
        // 1. 实时验证平台权限；2. 返回前端导航所需的身份。
        return new Operator(Long.toString(requireOperator(false)), "PLATFORM_OPERATOR");
    }

    @Transactional(readOnly = true)
    public List<Tenant> list(int page, int pageSize) {
        // 1. 校验独立平台角色；2. 分页返回企业元数据。
        requireOperator(false);
        long offset = offset(page, pageSize);
        return jdbc.query("SELECT " + TENANT_COLUMNS + " FROM tenants ORDER BY id DESC LIMIT ? OFFSET ?",
                PlatformTenantService::tenant, pageSize, offset);
    }

    @Transactional(readOnly = true)
    public Tenant detail(long id) {
        // 1. 校验平台角色；2. 读取企业详情。
        requireOperator(false);
        return find(id, false);
    }

    @Transactional(readOnly = true)
    public List<Action> actions(long id, int page, int pageSize) {
        // 1. 校验平台角色与企业存在性；2. 读取该企业的平台动作记录。
        requireOperator(false);
        find(id, false);
        long offset = offset(page, pageSize);
        return jdbc.query("""
                SELECT a.id,a.actor_user_id,a.action,a.reason,a.before_state,a.after_state,
                       a.lifecycle_version,e.request_id,a.occurred_at
                FROM platform_tenant_actions a JOIN audit_events e ON e.id=a.audit_event_id
                WHERE a.tenant_id=? ORDER BY a.id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new Action(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5) == null ? null : State.valueOf(rs.getString(5)),
                State.valueOf(rs.getString(6)), rs.getLong(7), rs.getString(8),
                rs.getObject(9, LocalDateTime.class)), id, pageSize, offset);
    }

    @Transactional
    public Tenant open(PlatformTenantController.Open request) {
        // 1. 锁定并重新验证平台身份和首位管理员账号。
        long actor = requireOperator(true); // 仅从登录上下文取得操作者。
        var admins = jdbc.queryForList("SELECT id FROM users WHERE account=? AND enabled=1 FOR UPDATE",
                Long.class, request.administratorAccount().strip()); // 锁住启用账号至事务提交。
        if (admins.isEmpty()) throw error(HttpStatus.NOT_FOUND, "首位企业管理员账号不存在或未启用");
        // 2. 创建企业和成员关系，分配企业管理员角色。
        try {
            jdbc.update("INSERT INTO tenants(tenant_code,name) VALUES (?,?)",
                    request.tenantCode().strip(), request.name().strip()); // 企业编号由唯一约束保护。
        } catch (DuplicateKeyException conflict) {
            throw error(HttpStatus.CONFLICT, "企业编号已存在");
        }
        long id = insertedId(); // 同一事务连接读取刚生成的企业 ID。
        jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id) VALUES (?,?)", id, admins.get(0));
        long membership = insertedId(); // 取得首位管理员的成员关系 ID。
        jdbc.update("""
                INSERT INTO roles(role_code,name) VALUES ('TENANT_ADMIN','企业管理员')
                ON DUPLICATE KEY UPDATE role_code=role_code
                """); // 空库可开通，已有角色保留。
        int granted = jdbc.update("""
                INSERT INTO tenant_member_roles(tenant_id,membership_id,role_id)
                SELECT ?,?,id FROM roles WHERE role_code='TENANT_ADMIN'
                """, id, membership); // 仅授予本企业的管理员角色。
        if (granted != 1) throw new IllegalStateException("首位企业管理员角色写入失败");
        // 3. 在同一事务写入平台动作与审计。
        record(actor, id, Event.TENANT_OPENED, request.reason().strip(), null, State.ACTIVE, 0);
        return find(id, false);
    }

    @Transactional
    public Tenant change(long id, PlatformTenantController.Change request, State target) {
        // 1. 锁定平台授权及企业，校验状态和版本。
        long actor = requireOperator(true); // 授权撤销不能越过本次事务。
        Tenant previous = find(id, true); // 与企业写入口共用企业行锁。
        if (previous.version() != request.expectedVersion() || previous.state() == target) {
            throw error(HttpStatus.CONFLICT, "企业状态或版本已变更，请刷新后重试");
        }
        // 2. 更新已有启停字段，增加生命周期版本。
        int rows = jdbc.update("""
                UPDATE tenants SET enabled=?,lifecycle_version=lifecycle_version+1
                WHERE id=? AND lifecycle_version=?
                """, target == State.ACTIVE, id, previous.version()); // 保留成员及角色记录。
        if (rows != 1) throw error(HttpStatus.CONFLICT, "企业状态或版本已变更，请刷新后重试");
        // 3. 同事务保存审计与动作记录，失败时整体回滚。
        Event action = target == State.ACTIVE ? Event.TENANT_RESUMED : Event.TENANT_SUSPENDED;
        record(actor, id, action, request.reason().strip(), previous.state(), target, previous.version() + 1);
        return find(id, false);
    }

    private long requireOperator(boolean lock) {
        // 1. 获取可信登录账号；2. 逐次查询独立平台角色。
        long actor = access.requireEnabledUserId();
        String query = """
                SELECT p.user_id FROM platform_operator_roles p JOIN users u ON u.id=p.user_id
                WHERE p.user_id=? AND p.role_code='PLATFORM_OPERATOR' AND p.enabled=1 AND u.enabled=1
                """;
        if (jdbc.queryForList(query + (lock ? " FOR UPDATE" : ""), Long.class, actor).isEmpty()) {
            throw error(HttpStatus.FORBIDDEN, "需要平台运营权限");
        }
        return actor;
    }

    private Tenant find(long id, boolean lock) {
        var result = jdbc.query("SELECT " + TENANT_COLUMNS + " FROM tenants WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), PlatformTenantService::tenant, id);
        if (result.isEmpty()) throw error(HttpStatus.NOT_FOUND, "企业不存在");
        return result.get(0);
    }

    private void record(long actor, long tenantId, Event action, String reason, State before, State after, long version) {
        // 1. 平台审计不设置企业查询归属，摘要仅含固定状态和版本。
        String requestId = MDC.get("requestId"); // 复用服务端请求编号，不接受请求体覆盖。
        if (requestId != null && !requestId.matches("[A-Za-z0-9_-]{1,36}")) requestId = null;
        jdbc.update("""
                INSERT INTO audit_events(actor_user_id,actor_type,action,object_type,object_id,outcome,
                    request_id,changes_json,occurred_at)
                VALUES (?,'PLATFORM',?,'TENANT',?,'SUCCESS',?,
                    JSON_OBJECT('state',?,'version',?),CURRENT_TIMESTAMP(6))
                """, actor, action.name(), tenantId, requestId, after.name(), version);
        // 2. 原因只写入受平台角色保护的动作记录，并链接统一审计。
        jdbc.update("""
                INSERT INTO platform_tenant_actions(tenant_id,actor_user_id,audit_event_id,action,
                    reason,before_state,after_state,lifecycle_version)
                VALUES (?,?,?,?,?,?,?,?)
                """, tenantId, actor, insertedId(), action.name(), reason,
                before == null ? null : before.name(), after.name(), version);
    }

    private long insertedId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static Tenant tenant(ResultSet rs, int row) throws SQLException {
        return new Tenant(rs.getString("id"), rs.getString("tenant_code"), rs.getString("name"),
                rs.getBoolean("enabled") ? State.ACTIVE : State.SUSPENDED, rs.getLong("lifecycle_version"),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));
    }

    private static long offset(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw error(HttpStatus.BAD_REQUEST, "分页参数无效");
        return (page - 1L) * pageSize;
    }

    private static ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }
}
