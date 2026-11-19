package com.ai;

import com.auth.mapper.AuthRoleMapper;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 内部请求的主体复核：每次调用重新查账号、企业、成员与角色。 */
@Component
public class AiAuthority {
    private final JdbcTemplate db;
    private final AuthRoleMapper roles;

    public record Subject(long tenantId, long userId, long membershipId, Set<String> roleCodes) {
        public Subject {
            if (tenantId <= 0 || userId <= 0 || membershipId <= 0) throw new IllegalArgumentException("AI_INVALID_SUBJECT");
            roleCodes = Set.copyOf(roleCodes);
        }

        public boolean admin() {
            return roleCodes.contains("TENANT_ADMIN");
        }
    }

    public AiAuthority(JdbcTemplate db, AuthRoleMapper roles) {
        this.db = db;
        this.roles = roles;
    }

    /** 执行流程 1校验主键 2查账号/企业/成员当前状态 3读取当前角色。 */
    public Subject validate(long tenant, long user) {
        if (tenant <= 0 || user <= 0) throw denied();
        List<Long> members = db.queryForList("""
                SELECT m.id FROM tenant_memberships m
                JOIN users u ON u.id=m.user_id JOIN tenants t ON t.id=m.tenant_id
                WHERE m.tenant_id=? AND m.user_id=? AND m.enabled=1 AND u.enabled=1 AND t.enabled=1 FOR SHARE
                """, Long.class, tenant, user);
        if (members.size() != 1) throw denied();
        return new Subject(tenant, user, members.get(0), Set.copyOf(roles.selectRoleCodes(tenant, members.get(0))));
    }

    private ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "AI_SUBJECT_UNAVAILABLE");
    }
}
