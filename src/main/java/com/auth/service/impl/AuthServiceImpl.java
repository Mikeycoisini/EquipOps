package com.auth.service.impl;

import com.auth.model.AuthSessionAttributes;
import com.auth.model.LoginUser;
import com.auth.model.TenantAccess;
import com.auth.service.AuthService;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.equipops.domain.dto.auth.ActiveTenantRequestDto;
import com.equipops.domain.dto.auth.LoginRequestDto;
import com.equipops.domain.entity.UserEntity;
import com.equipops.domain.vo.AuthUserVO;
import com.equipops.domain.vo.LoginResponseVO;
import com.equipops.domain.vo.TenantVO;
import com.workorder.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 登录和企业选择的业务入口。
 * 阅读顺序：login 让服务器记住登录身份；me 查看身份；activateTenant 选择企业；logout 清除身份。
 * AuthenticationManager 负责验证身份，SecurityContext 保存认证结果，Session 负责跨请求保存它。
 * 企业成员的新增、修改、停用属于另一个业务模块，这里不处理。
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    /** 认证总入口：内部调用数据库用户加载和密码校验组件。 */
    private final AuthenticationManager authenticationManager;
    /** 登录成功后执行：更新会话 ID、清除旧 CSRF 令牌。 */
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    /** 将认证结果保存到请求及 Session，供后续请求恢复登录身份。 */
    private final SecurityContextRepository securityContextRepository;
    /** 管理 CSRF 令牌，退出时需要清除。 */
    private final CsrfTokenRepository csrfTokenRepository;
    /** 统一检查当前用户、企业及成员关系，避免各业务重复判断。 */
    private final CurrentAccessService currentAccessService;
    /** 读取用户基本信息，仅更新登录时间。 */
    private final UserMapper userMapper;
    /** Spring 提供当前请求的代理，不是在单例 Service 中保存某个用户的固定请求。 */
    private final HttpServletRequest currentRequest;

    /**
     * 登录流程：校验密码 → 记录登录时间 → 更新会话 → 保存认证结果 → 返回公开用户资料。
     * 密码错误会抛 AuthenticationException，由认证异常处理器转换为 401。
     */
    @Override
    public LoginResponseVO login(LoginRequestDto request, HttpServletRequest servletRequest,
                                 HttpServletResponse servletResponse) {
        // 1. unauthenticated 表示这些凭据尚未通过校验，不能当作可信登录结果。
        // 账号去掉首尾空格；密码保持原样，不能 trim 后改变用户实际输入的密码。
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        request.getAccount().trim(), request.getPassword()));
        // principal 是校验通过的用户主体，由 DatabaseUserDetailsService 创建。
        // 认证组件会擦除主体中的密码哈希，后续只使用用户 ID、账号等信息。
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();

        // 2. 仅更新登录时间，避免用完整实体更新覆盖其他用户字段。
        // WHERE 中再次检查 enabled，处理账号在密码校验后恰好被停用的情况。
        int updated = userMapper.update(null, new LambdaUpdateWrapper<UserEntity>()
                .eq(UserEntity::getId, loginUser.getUserId())
                .eq(UserEntity::getEnabled, true)
                .set(UserEntity::getLastLoginAt, LocalDateTime.now()));
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不可用，请重新登录");
        }

        // 3. 登录成功后换会话 ID，避免沿用他人预先指定的会话；同时清除旧 CSRF。
        // Session 保存服务器端状态，浏览器只通过 JSESSIONID Cookie 找回对应会话。
        sessionAuthenticationStrategy.onAuthentication(authentication, servletRequest, servletResponse);
        HttpSession session = servletRequest.getSession(true);
        // 重新登录必须重新选择企业，不能把上一次身份的企业选择带进来。
        session.removeAttribute(AuthSessionAttributes.ACTIVE_TENANT_ID);
        servletRequest.removeAttribute(AuthSessionAttributes.REQUEST_TENANT_ID);

        // 4. 新建安全上下文并放入认证结果，供本次请求读取当前身份。
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // Holder 主要服务本次请求；显式保存后，下次请求才能从 Session 恢复身份。
        securityContextRepository.saveContext(context, servletRequest, servletResponse);

        // 5. 响应体只返回公开资料。登录凭据在会话 Cookie 中，不返回密码或伪造 token。
        return new LoginResponseVO(loginUser.getUserId(), loginUser.getAccount(), loginUser.getDisplayName());
    }

    /** 查询“我是谁”，已经选择企业时还要重新检查该企业的成员和角色。 */
    @Override
    public AuthUserVO me() {
        // 1. 从可信认证结果取得用户 ID，再读取数据库最新的显示资料。
        Long userId = currentAccessService.requireEnabledUserId();
        UserEntity user = userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录身份已失效，请重新登录");
        }
        // 2. 先组装全局身份：未选择企业时，企业和成员 ID 为空，企业角色也是空集合。
        AuthUserVO result = new AuthUserVO(user.getId(), user.getAccount(), user.getDisplayName(),
                null, null, Set.of());
        // false 表示只取已有会话，不为了查询身份额外新建一个 Session。
        HttpSession session = currentRequest.getSession(false);
        if (session != null && session.getAttribute(AuthSessionAttributes.ACTIVE_TENANT_ID) != null) {
            // 3. 不直接返回 Session 中的企业 ID；还要查询最新企业、成员关系和角色。
            TenantAccess access = currentAccessService.requireTenantAccess();
            TenantVO tenant = new TenantVO();
            BeanUtils.copyProperties(access.getTenant(), tenant);
            result.setActiveTenant(tenant);
            result.setMembershipId(access.getMembershipId());
            result.setRoleCodes(access.getRoleCodes());
        }
        return result;
    }

    /** 切换企业：前端提出目标 ID，服务器验证权限后才能保存。 */
    @Override
    public AuthUserVO activateTenant(ActiveTenantRequestDto request, HttpServletRequest servletRequest) {
        // 验证失败会直接抛异常，因此不会执行后面的 setAttribute，也不会切换成功。
        TenantAccess access = currentAccessService.validateTenantSelection(request.getTenantId());
        servletRequest.getSession(true).setAttribute(AuthSessionAttributes.ACTIVE_TENANT_ID,
                access.getTenant().getId());
        // 同一次切换请求之后的读取使用新选择，不沿用原请求快照。
        servletRequest.setAttribute(AuthSessionAttributes.REQUEST_TENANT_ID, access.getTenant().getId());
        // 返回切换后的身份摘要，前端不需要自行猜测当前企业和角色。
        return me();
    }

    /** 退出必须同时处理 CSRF、服务器会话、当前认证上下文和浏览器会话 Cookie。 */
    @Override
    public void logout(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        // 退出清理组件需要本次请求的认证对象；它不是前端提交的用户资料。
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 先清除与旧会话关联的 CSRF，下一次登录前需要重新获取。
        new CsrfLogoutHandler(csrfTokenRepository).logout(servletRequest, servletResponse, authentication);
        // 框架组件负责使 Session 失效，并清空 Holder 和已保存的安全上下文。
        SecurityContextLogoutHandler handler = new SecurityContextLogoutHandler();
        handler.setSecurityContextRepository(securityContextRepository);
        handler.logout(servletRequest, servletResponse, authentication);
        // 通知浏览器清除 Cookie；仅清 Cookie 而不失效服务器 Session 并不完整。
        new CookieClearingLogoutHandler("JSESSIONID").logout(servletRequest, servletResponse, authentication);
        servletRequest.removeAttribute(AuthSessionAttributes.REQUEST_TENANT_ID);
    }
}
