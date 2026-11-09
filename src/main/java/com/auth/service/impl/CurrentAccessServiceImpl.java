package com.auth.service.impl;

import com.auth.mapper.AuthRoleMapper;
import com.auth.model.AuthSessionAttributes;
import com.auth.model.LoginUser;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.entity.TenantMembershipEntity;
import com.equipops.domain.entity.UserEntity;
import com.tenant.mapper.TenantMapper;
import com.tenant.mapper.TenantMembershipMapper;
import com.workorder.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 各业务共用的“当前是谁、属于哪个企业、具有什么角色”检查入口。
 * 用户 ID 来自登录主体，企业选择来自服务器 Session；请求头和请求体都不能直接当作授权证据。
 * 每次重新查询状态和角色，让账号停用、成员撤销、角色调整影响后续请求。
 */
@Service
@RequiredArgsConstructor
public class CurrentAccessServiceImpl implements CurrentAccessService {
    /** 查询全局账号的最新状态。 */
    private final UserMapper userMapper;
    /** 查询企业是否仍存在、启用。 */
    private final TenantMapper tenantMapper;
    /** 查询“用户在某企业内”的有效成员关系。 */
    private final TenantMembershipMapper membershipMapper;
    /** 按企业和成员关系读取角色，不能取其他企业的角色。 */
    private final AuthRoleMapper roleMapper;
    /** Spring 注入的当前请求代理，用于读取 Session 和请求内的企业选择快照。 */
    private final HttpServletRequest request;

    /** 登录成功不代表账号永远有效，所以认证主体检查之后还要查一次数据库。 */
    @Override
    public Long requireEnabledUserId() {
        // 1. 读取框架在本次请求恢复的认证结果，不从 URL 或 JSON 中取得用户身份。
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 2. instanceof 同时检查类型并声明 loginUser；匿名主体也无法通过这个检查。
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        // 3. Session 中可能还保存着过去的登录主体，数据库状态才是本次判断依据。
        UserEntity user = userMapper.selectById(loginUser.getUserId());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录身份已失效，请重新登录");
        }
        return user.getId();
    }

    /** 只需要企业 ID 的业务也复用完整检查，不能省略成员关系校验。 */
    @Override
    public Long requireActiveTenantId() {
        return requireTenantAccess().getTenant().getId();
    }

    /** 取得本次请求固定的企业选择，再验证它现在是否仍然可进入。 */
    @Override
    public TenantAccess requireTenantAccess() {
        requireEnabledUserId();
        // 同一次请求固定企业选择，避免其他标签页切换企业影响执行中的请求。
        Object selected = request.getAttribute(AuthSessionAttributes.REQUEST_TENANT_ID);
        if (selected == null) {
            // 没有已有 Session 就视为没有企业选择，不为读取业务范围新建会话。
            HttpSession session = request.getSession(false);
            selected = session == null ? null : session.getAttribute(AuthSessionAttributes.ACTIVE_TENANT_ID);
            if (selected instanceof Long) {
                request.setAttribute(AuthSessionAttributes.REQUEST_TENANT_ID, selected);
            }
        }
        // 会话属性类型为 Object，确认是 Long 后再使用；没有选择企业与未登录是两回事。
        if (!(selected instanceof Long tenantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先选择当前企业");
        }
        return validateTenantSelection(tenantId);
    }

    /** 既用于首次选择企业，也用于后续请求重新验证，避免出现两套不同的准入规则。 */
    @Override
    public TenantAccess validateTenantSelection(Long tenantId) {
        Long userId = requireEnabledUserId();
        if (tenantId == null || tenantId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "企业 ID 无效");
        }
        // 企业有效和成员关系有效必须同时满足。
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        TenantMembershipEntity membership = membershipMapper.selectOne(
                new LambdaQueryWrapper<TenantMembershipEntity>()
                        // 三个条件是 AND：指定企业、当前用户、成员关系启用。
                        .eq(TenantMembershipEntity::getTenantId, tenantId)
                        .eq(TenantMembershipEntity::getUserId, userId)
                        .eq(TenantMembershipEntity::getEnabled, true));
        if (tenant == null || !Boolean.TRUE.equals(tenant.getEnabled()) || membership == null) {
            // 不区分其他企业不存在、已停用或成员关系无效，避免泄露企业信息。
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前企业不可用或成员关系已失效");
        }
        // LinkedHashSet 去重并保留 SQL 的角色排序；不从其他企业或全局 user_roles 取角色。
        return new TenantAccess(userId, tenant, membership.getId(),
                new LinkedHashSet<>(roleMapper.selectRoleCodes(tenantId, membership.getId())));
    }

    /** 已登录、是企业成员和是企业管理员，是三层不同的条件。 */
    @Override
    public Long requireTenantAdminTenantId() {
        TenantAccess access = requireTenantAccess();
        if (!access.getRoleCodes().contains(AuthSessionAttributes.TENANT_ADMIN_ROLE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要当前企业的管理员权限");
        }
        return access.getTenant().getId();
    }
}
