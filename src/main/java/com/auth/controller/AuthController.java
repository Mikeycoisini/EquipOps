package com.auth.controller;

import com.auth.service.AuthService;
import com.common.response.Result;
import com.equipops.domain.dto.auth.ActiveTenantRequestDto;
import com.equipops.domain.dto.auth.LoginRequestDto;
import com.equipops.domain.vo.AuthUserVO;
import com.equipops.domain.vo.CsrfTokenVO;
import com.equipops.domain.vo.LoginResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session 认证入口；登录及所有写操作均保留 CSRF 校验。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    /** @RequiredArgsConstructor 为 final 字段生成构造器，由 Spring 注入业务实现。 */
    private final AuthService authService;

    /** 获取 CSRF 令牌；登录前、登录后及退出后分别重新获取。 */
    @GetMapping("/csrf")
    public Result<CsrfTokenVO> csrf(CsrfToken token) {
        // 读取令牌时触发框架的延迟加载；客户端写请求用 headerName 对应的请求头提交 token。
        // 这里的 token 证明写请求意图，不证明用户已经登录。
        return Result.success(new CsrfTokenVO(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    /** 验证账号密码并保存服务端登录状态。 */
    @PostMapping("/login")
    public Result<LoginResponseVO> login(@Valid @RequestBody LoginRequestDto request,
                                         HttpServletRequest servletRequest,
                                         HttpServletResponse servletResponse) {
        // @Valid 检查 LoginRequestDto 上的约束；Servlet 请求和响应用于读写会话与 Cookie。
        return Result.success(authService.login(request, servletRequest, servletResponse));
    }

    /** 查询当前有效身份和已验证的当前企业摘要。 */
    @GetMapping("/me")
    public Result<AuthUserVO> me() {
        // 不接收 userId，当前用户由服务器从登录状态中确定。
        return Result.success(authService.me());
    }

    /** 验证有效成员关系后切换当前企业。 */
    @PostMapping("/active-tenant")
    public Result<AuthUserVO> activateTenant(@Valid @RequestBody ActiveTenantRequestDto request,
                                            HttpServletRequest servletRequest) {
        // tenantId 只是目标选择，业务层验证成员关系后才真正写入 Session。
        return Result.success(authService.activateTenant(request, servletRequest));
    }

    /** 退出并失效当前会话。 */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        // 清理动作全部成功后再返回成功，避免只清前端显示状态却留下服务器会话。
        authService.logout(servletRequest, servletResponse);
        return Result.success();
    }
}
