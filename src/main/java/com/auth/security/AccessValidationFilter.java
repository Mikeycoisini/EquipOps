package com.auth.security;

import com.auth.service.CurrentAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/**
 * 在到达业务 Controller 前执行通用检查；OncePerRequestFilter 负责常规请求中的单次执行控制。
 * 它检查账号及指定企业入口，不代替 Service 内的对象归属和 SQL 查询范围校验。
 * 由 SecurityConfig 手工加入过滤链，不加 @Component，避免重复注册。
 */
@RequiredArgsConstructor
public class AccessValidationFilter extends OncePerRequestFilter {
    /** 统一验证逻辑，避免在过滤器中重复写数据库规则。 */
    private final CurrentAccessService currentAccessService;
    /** 过滤器不能靠认证 Controller 的异常处理器响应，需自行输出 JSON。 */
    private final ApiSecurityResponse responses;
    /** 已知成员写动作的拒绝记录；保留原过滤授权规则，不采集正文。 */
    private final com.audit.service.AuditEventService audit;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AccessValidationFilter.class);

    /**
     * true 只表示跳过本过滤器，不代表 permitAll，也不会关闭其他过滤器的 CSRF 检查。
     * 退出允许已停用账号清理旧会话；它仍受安全链的登录和 CSRF 门槛保护。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/auth/login") || path.equals("/api/auth/csrf")
                || path.equals("/api/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            // 已保存的登录身份不一定仍有效，先检查数据库中的账号状态。
            currentAccessService.requireEnabledUserId();
            String path = request.getServletPath();
            // 成员管理需要当前企业管理员；默认厂区、仓库查询只要求有效企业成员。
            if (matches(path, "/api/tenant-members")) {
                currentAccessService.requireTenantAdminTenantId();
            } else if (matches(path, "/api/sites") || matches(path, "/api/warehouses")) {
                currentAccessService.requireActiveTenantId();
            }
        } catch (ResponseStatusException exception) {
            // 已有403语义保持不变，只为已知敏感写动作补充可信身份的拒绝审计。
            if (exception.getStatusCode().value() == 403) recordMemberDenial(request);
            responses.write(response, exception.getStatusCode().value(), exception.getReason());
            // 检查失败时必须结束，不把请求继续交给后面的组件。
            return;
        }
        // 通过检查，交给下一层过滤器；整个过滤链通过后才会进入 Controller。
        chain.doFilter(request, response);
    }

    private void recordMemberDenial(HttpServletRequest request) {
        // 1. 动作从固定路由和HTTP方法确定，禁止读取正文或客户端企业身份。
        String path = request.getServletPath();
        com.equipops.domain.enums.AuditAction action = null;
        if ("POST".equals(request.getMethod()) && path.equals("/api/tenant-members")) {
            action = com.equipops.domain.enums.AuditAction.MEMBER_ADDED;
        } else if ("PUT".equals(request.getMethod()) && path.matches("/api/tenant-members/[1-9][0-9]*/roles")) {
            action = com.equipops.domain.enums.AuditAction.MEMBER_ROLES_CHANGED;
        } else if ("PATCH".equals(request.getMethod()) && path.matches("/api/tenant-members/[1-9][0-9]*/status")) {
            action = com.equipops.domain.enums.AuditAction.MEMBER_STATUS_CHANGED;
        }
        if ("PUT".equals(request.getMethod()) && path.matches("/api/tenant-members/[1-9][0-9]*/site-scopes")) {
            action = com.equipops.domain.enums.AuditAction.MEMBER_SITE_SCOPES_CHANGED;
        } else if ("POST".equals(request.getMethod()) && path.equals("/api/sites")) {
            action = com.equipops.domain.enums.AuditAction.SITE_CREATED;
        } else if ("PUT".equals(request.getMethod()) && path.matches("/api/sites/[1-9][0-9]*")) {
            action = com.equipops.domain.enums.AuditAction.SITE_UPDATED;
        }
        // 2. 其他路径及只读查询不形成全量请求审计。
        if (action == null) return;
        com.auth.model.TenantAccess actor;
        try {
            // 3. 必须重新建立有效当前企业身份，停用账号/成员和猜测企业不作为归属依据。
            actor = currentAccessService.requireTenantAccess();
        } catch (RuntimeException unavailable) {
            // 包括身份重查的数据库故障；补充审计不能替换已经确定的403。
            // 无可信身份时不写任何推测归属记录，也不输出异常正文。
            return;
        }
        try {
            // 4. 目标对象未在业务中确认，因此不记录请求中的目标ID。
            audit.appendDenied(new com.equipops.domain.dto.audit.AuditAppendCommand(
                    actor.getTenant().getId(), actor.getUserId(), action,
                    action == com.equipops.domain.enums.AuditAction.SITE_CREATED || action == com.equipops.domain.enums.AuditAction.SITE_UPDATED
                            ? com.equipops.domain.enums.AuditObjectType.SITE : com.equipops.domain.enums.AuditObjectType.MEMBER,
                    null, java.util.Map.of(), "FORBIDDEN", null));
        } catch (RuntimeException unavailable) {
            // 5. 审计存储错误不能替换原403，日志也不输出异常正文或凭据。
            LOG.warn("成员过滤拒绝审计未写入，action={}", action);
        }
    }

    /** 同时匹配根路径和子路径，避免把 tenant-members-other 误当作成员接口。 */
    private boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
