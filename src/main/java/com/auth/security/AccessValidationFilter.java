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
            responses.write(response, exception.getStatusCode().value(), exception.getReason());
            // 检查失败时必须结束，不把请求继续交给后面的组件。
            return;
        }
        // 通过检查，交给下一层过滤器；整个过滤链通过后才会进入 Controller。
        chain.doFilter(request, response);
    }

    /** 同时匹配根路径和子路径，避免把 tenant-members-other 误当作成员接口。 */
    private boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
