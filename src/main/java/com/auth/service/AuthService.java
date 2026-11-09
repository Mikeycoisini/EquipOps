package com.auth.service;

import com.equipops.domain.dto.auth.ActiveTenantRequestDto;
import com.equipops.domain.dto.auth.LoginRequestDto;
import com.equipops.domain.vo.AuthUserVO;
import com.equipops.domain.vo.LoginResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 认证业务契约：Controller 只接收参数和包装响应，流程写在 AuthServiceImpl。
 * 登录与退出需要实际请求、响应，是因为它们会修改 Session 和 Cookie。
 */
public interface AuthService {
    /** 认证并保存 Session；重新登录会清除原企业选择。 */
    LoginResponseVO login(LoginRequestDto request, HttpServletRequest servletRequest,
                          HttpServletResponse servletResponse);

    /** 查询当前有效身份，有企业选择时重新验证成员与角色。 */
    AuthUserVO me();

    /** 验证成员关系后切换企业。 */
    AuthUserVO activateTenant(ActiveTenantRequestDto request, HttpServletRequest servletRequest);

    /** 清除安全上下文、Session 与会话 Cookie。 */
    void logout(HttpServletRequest servletRequest, HttpServletResponse servletResponse);
}
