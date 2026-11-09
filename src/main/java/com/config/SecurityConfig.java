package com.config;

import com.auth.security.AccessValidationFilter;
import com.auth.security.ApiSecurityResponse;
import com.auth.service.CurrentAccessService;
import com.auth.service.impl.DatabaseUserDetailsService;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * 认证基础设施的装配位置：这里决定请求先经过哪些检查，不负责设备或工单业务。
 * 建议先读 AuthServiceImpl.login 理解登录过程，再回来看这些组件怎样连接。
 * @Bean 将方法返回的对象交给 Spring 管理，其他组件通过构造器注入复用。
 */
@Configuration
public class SecurityConfig {

    /** BCrypt 保存和比对密码哈希；不是可解密的密码加密，也不应比较两次 encode 的字符串。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 使用已有数据库用户和 BCrypt 校验账号密码。 */
    @Bean
    public AuthenticationManager authenticationManager(DatabaseUserDetailsService users,
                                                       PasswordEncoder passwordEncoder) {
        // Provider 通过 users 查账号，再通过 passwordEncoder.matches 校验输入密码。
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        // 业务只调用 AuthenticationManager，不必自己重复写账号和密码认证流程。
        return new ProviderManager(provider);
    }

    /** 请求属性服务本次请求及相关转发；HttpSession 保存跨请求的登录状态。 */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    /** 将期望的 CSRF 值保存在服务器 Session，客户端从 csrf 接口获取可提交的令牌。 */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    /** 自定义登录入口也必须调用会话固定防护和 CSRF 更新策略。 */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        // Composite 表示按顺序执行多个策略：先换会话 ID，再清除旧 CSRF。
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    /**
     * 为 /api/** 装配安全过滤链。
     * 调用者先通过登录、CSRF 和访问检查，才有机会到达 Controller。
     * 这是认证与部分企业入口检查，不等于旧业务已补齐 SQL 层的企业隔离。
     */
    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, AuthenticationManager authenticationManager,
                                           SecurityContextRepository securityContextRepository,
                                           CsrfTokenRepository csrfTokenRepository,
                                           CurrentAccessService currentAccessService,
                                           ApiSecurityResponse responses,
                                           com.audit.service.AuditEventService auditEventService) throws Exception {
        // 此链只处理 /api 范围；csrf 获取与登录允许匿名，其余接口需要登录。
        http.securityMatcher("/api/**")
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .anyRequest().authenticated())
                // 自定义登录方法必须显式 saveContext，不能仅设置 Holder 后就返回成功。
                .securityContext(context -> context
                        .requireExplicitSave(true)
                        .securityContextRepository(securityContextRepository))
                // IF_REQUIRED 表示需要保存状态时再创建 Session，不为每个请求强制创建。
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // 保留写请求的 CSRF 防护。Cookie 会自动随请求发送，所以仍需校验写入意图。
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                // API 返回 JSON，不需要保存请求并在登录后跳回某个网页。
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                // 使用自己的 JSON 登录和退出接口，关闭默认表单登录、Basic 和默认退出入口。
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // 安全过滤器中的异常发生在 Controller 外，必须在这里输出 JSON 错误。
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                responses.write(response, 401, "请先登录或重新登录"))
                        .accessDeniedHandler((request, response, exception) -> {
                            String message = exception instanceof MissingCsrfTokenException
                                    || exception instanceof InvalidCsrfTokenException
                                    ? "CSRF 校验失败，请重新获取令牌后重试" : "没有执行该操作的权限";
                            responses.write(response, 403, message);
                        }))
                // 匿名身份处理之后检查账号最新状态；过滤器只在此注册一次，不另加 @Component。
                .addFilterAfter(new AccessValidationFilter(currentAccessService, responses, auditEventService),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
