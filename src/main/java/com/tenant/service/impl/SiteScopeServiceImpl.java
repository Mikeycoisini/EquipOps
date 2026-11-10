package com.tenant.service.impl;

import com.audit.service.AuditEventService;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.dto.tenant.SiteScopeUpdateDto;
import com.equipops.domain.enums.AuditAction;
import com.equipops.domain.enums.AuditObjectType;
import com.equipops.domain.vo.SiteScopeVO;
import com.tenant.service.SiteScopeService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 成员范围事务服务；授权管理SQL始终显式限定企业，不经过业务数据权限拦截器。 */
@Service
@RequiredArgsConstructor
public class SiteScopeServiceImpl implements SiteScopeService {
    /** 响应版本采用JSON数值，只接受浏览器可精确表达的整数。 */
    private static final long MAX_VERSION = 9_007_199_254_740_991L;
    private final CurrentAccessService access;
    private final JdbcTemplate jdbc;
    private final AuditEventService audit;

    /** 执行流程：验证管理员→校验目标→读取版本与显式集合→返回字符串ID。 */
    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public SiteScopeVO getScopes(Long membershipId) {
        // 1. 读取仅对当前企业管理员开放，不接收客户端企业或操作者ID。
        Long tenantId = access.requireTenantAdminTenantId();
        // 2. 成员与集合在同一事务快照中读取，避免版本与集合前后不一致。
        requireId(membershipId);
        long version = memberVersion(tenantId, membershipId, false);
        // 3. 无授权记录就是空集合，不能回退为默认厂区或企业全范围。
        return view(membershipId, version, scopeIds(tenantId, membershipId, false));
    }

    /** 执行流程：验证输入→企业锁→成员锁→重验管理员→版本校验→厂区校验→原子替换→审计。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteScopeVO updateScopes(Long membershipId, SiteScopeUpdateDto request) {
        // 1. 固定当前可信企业及用户，拒绝普通成员通过服务直接调用修改范围。
        Long tenantId = access.requireTenantAdminTenantId();
        Long actorId = access.requireEnabledUserId();
        // 2. 对直接服务调用也执行容量、唯一性、正数和版本验证。
        requireId(membershipId);
        List<Long> requested = validatedSites(request);
        // 3. 先锁企业，与成员启停及角色修改保持一致锁序。
        var tenants = jdbc.queryForList("SELECT enabled FROM tenants WHERE id = ? FOR UPDATE", tenantId);
        if (tenants.isEmpty() || !enabled(tenants.get(0).get("enabled"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前企业不可用");
        }
        // 4. 再锁当前企业的目标成员，同时取得最新版本，跨企业对象统一404。
        long version = memberVersion(tenantId, membershipId, true);
        // 5. 等待企业锁期间管理员可能被撤权；使用锁定读重验账号、成员和角色。
        var admins = jdbc.queryForList("SELECT m.user_id FROM tenant_memberships m "
                + "JOIN users u ON u.id = m.user_id "
                + "JOIN tenant_member_roles mr ON mr.tenant_id = m.tenant_id AND mr.membership_id = m.id "
                + "JOIN roles r ON r.id = mr.role_id "
                + "WHERE m.tenant_id = ? AND m.user_id = ? AND m.enabled = TRUE AND u.enabled = TRUE "
                + "AND r.role_code = 'TENANT_ADMIN' FOR UPDATE", Long.class, tenantId, actorId);
        if (admins.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前企业管理员权限已失效");
        }
        // 6. 即使提交的集合相同，旧版本请求仍返回冲突。
        if (version != request.getExpectedVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成员厂区范围版本已变化，请刷新后重试");
        }
        // 7. 新集合只允许本企业启用厂区，锁住目标以免校验后并发停用。
        if (!requested.isEmpty()) {
            var args = new ArrayList<Object>();
            args.add(tenantId);
            args.addAll(requested);
            String placeholders = String.join(",", Collections.nCopies(requested.size(), "?"));
            List<Long> found = jdbc.queryForList("SELECT id FROM sites WHERE tenant_id = ? AND enabled = TRUE "
                    + "AND id IN (" + placeholders + ") ORDER BY id FOR UPDATE", Long.class, args.toArray());
            if (!new HashSet<>(found).equals(new HashSet<>(requested))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "包含不存在、停用或不属于当前企业的厂区");
            }
        }
        // 8. 当前读取得原范围，集合相同不递增、不重复审计。
        List<Long> before = scopeIds(tenantId, membershipId, true);
        if (before.equals(requested)) return view(membershipId, version, before);
        // 9. 版本必须保持JSON安全整数，避免浏览器舍入后破坏乐观锁。
        if (version == MAX_VERSION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成员厂区范围版本已达上限");
        }
        // 10. 清除本成员旧集合，并逐项插入由服务器确认的厂区及授予人。
        if (jdbc.update("DELETE FROM tenant_member_site_scopes WHERE tenant_id = ? AND membership_id = ?", tenantId, membershipId) != before.size()) {
            throw new IllegalStateException("成员厂区范围删除计数不一致");
        }
        for (Long siteId : requested) {
            if (jdbc.update("INSERT INTO tenant_member_site_scopes (tenant_id, membership_id, site_id, granted_by) VALUES (?, ?, ?, ?)",
                    tenantId, membershipId, siteId, actorId) != 1) {
                throw new IllegalStateException("成员厂区范围保存失败");
            }
        }
        // 11. 版本条件更新应影响恰好一行；任何异常回滚整个替换及审计。
        if (jdbc.update("UPDATE tenant_memberships SET site_scope_version = site_scope_version + 1 "
                + "WHERE tenant_id = ? AND id = ? AND site_scope_version = ?", tenantId, membershipId, version) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成员厂区范围版本已变化，请刷新后重试");
        }
        // 12. 成功事件与业务共用事务，只保存厂区ID集合与版本，不保存自由文本。
        audit.appendSuccess(new AuditAppendCommand(tenantId, actorId, AuditAction.MEMBER_SITE_SCOPES_CHANGED,
                AuditObjectType.MEMBER, membershipId, Map.of("field", "siteIds", "before", strings(before),
                "after", strings(requested), "version", version + 1), null, null));
        // 13. 返回本次已持久化的完整集合及新版本。
        return view(membershipId, version + 1, requested);
    }

    private void requireId(Long membershipId) {
        // 成员关系ID必须为正数，不将非法值发送到SQL。
        if (membershipId == null || membershipId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成员关系ID无效");
    }

    private List<Long> validatedSites(SiteScopeUpdateDto request) {
        // 空集合合法，空字段、负版本或超容量请求均拒绝。
        if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() < 0
                || request.getExpectedVersion() > MAX_VERSION || request.getSiteIds() == null || request.getSiteIds().size() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "厂区范围参数无效");
        }
        // 不静默去重，重复ID必须向调用方明确报错。
        var unique = new TreeSet<Long>();
        for (Long id : request.getSiteIds()) {
            if (id == null || id <= 0 || !unique.add(id)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "厂区ID必须为唯一正数");
        }
        // 稳定排序用于集合比较、锁定查询及响应。
        return List.copyOf(unique);
    }

    private long memberVersion(Long tenantId, Long membershipId, boolean lock) {
        // SQL固定企业及启用成员；不因管理员身份绕过目标企业检查。
        var rows = jdbc.queryForList("SELECT site_scope_version, enabled FROM tenant_memberships "
                + "WHERE tenant_id = ? AND id = ? AND enabled = TRUE" + (lock ? " FOR UPDATE" : ""), tenantId, membershipId);
        if (rows.isEmpty() || !enabled(rows.get(0).get("enabled"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成员不存在或已停用");
        // 校验数据库版本，避免将不能精确表达的值传给浏览器。
        long version = ((Number) rows.get(0).get("site_scope_version")).longValue();
        if (version < 0 || version > MAX_VERSION) throw new ResponseStatusException(HttpStatus.CONFLICT, "成员厂区范围版本不可用");
        return version;
    }

    private List<Long> scopeIds(Long tenantId, Long membershipId, boolean lock) {
        // 不连接业务厂区表，保留管理员对已授予停用厂区记录的可追溯读取。
        return jdbc.queryForList("SELECT site_id FROM tenant_member_site_scopes WHERE tenant_id = ? "
                + "AND membership_id = ? ORDER BY site_id" + (lock ? " FOR UPDATE" : ""), Long.class, tenantId, membershipId);
    }

    private boolean enabled(Object value) {
        // JDBC驱动可能将BOOLEAN映射为布尔或整数，二者按同一规则读取。
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() == 1;
    }

    private List<String> strings(List<Long> ids) {
        // BIGINT标识符在JSON中使用十进制字符串。
        return ids.stream().map(String::valueOf).toList();
    }

    private SiteScopeVO view(Long membershipId, long version, List<Long> ids) {
        // 响应只包含目标成员、乐观锁版本及显式集合。
        return new SiteScopeVO(membershipId.toString(), version, strings(ids));
    }
}
