package com.auth.scope;

import com.auth.service.CurrentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** INSERT前锁定当前授权；与企业→成员→厂区的范围替换锁序一致。 */
@Service
@RequiredArgsConstructor
public class SiteWriteAccessService {
    private final CurrentAccessService access;
    private final JdbcTemplate jdbc;

    /** 1.验证可信主体；2.企业锁；3.成员和账号锁；4.当前角色；5.启用厂区及授权当前读。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSite(Long siteId) {
        lockSite(siteId, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSiteAsAdmin(Long siteId) {
        lockSite(siteId, true);
    }

    private void lockSite(Long siteId, boolean adminRequired) {
        var actor = access.requireTenantAccess(); // 身份只从服务端会话验证结果取得。
        if (siteId == null || siteId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "厂区ID无效"); // 拒绝非法父归属。
        long tenant = actor.getTenant().getId(); // 固定当前企业，不能跨请求或后台复用。
        var tenants = jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND enabled=1 FOR UPDATE", Long.class, tenant); // 与范围管理串行。
        if (tenants.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前企业不可用"); // 锁等待后重验状态。
        var members = jdbc.queryForList("SELECT m.id FROM tenant_memberships m JOIN users u ON u.id=m.user_id "
                + "WHERE m.tenant_id=? AND m.id=? AND m.user_id=? AND m.enabled=1 AND u.enabled=1 FOR UPDATE",
                Long.class, tenant, actor.getMembershipId(), actor.getUserId()); // 锁到事务结束，撤权不能穿过新增业务。
        if (members.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前成员关系已失效"); // 不信任旧RR身份快照。
        var roles = jdbc.queryForList("SELECT r.role_code FROM tenant_member_roles mr JOIN roles r ON r.id=mr.role_id "
                + "WHERE mr.tenant_id=? AND mr.membership_id=? FOR UPDATE", String.class, tenant, actor.getMembershipId()); // 角色也使用当前读。
        boolean admin = roles.contains("TENANT_ADMIN"); // 使用锁内读取的最新角色。
        if (adminRequired && !admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要当前企业的管理员权限");
        }
        var sites = jdbc.queryForList("SELECT id FROM sites WHERE tenant_id=? AND id=? AND enabled=1 FOR UPDATE",
                Long.class, tenant, siteId); // 厂区启停与新增业务串行，管理员也不能向停用厂区新增。
        if (sites.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "厂区不存在或未启用"); // 跨企业同样隐藏。
        if (!admin) { // 管理员范围只由锁定读取的最新角色判断。
            var grants = jdbc.queryForList("SELECT site_id FROM tenant_member_site_scopes WHERE tenant_id=? "
                    + "AND membership_id=? AND site_id=? FOR UPDATE", Long.class, tenant, actor.getMembershipId(), siteId); // 显式授权当前读。
            if (grants.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前没有该厂区的写入权限"); // 空集合不放行。
        }
    }
}
