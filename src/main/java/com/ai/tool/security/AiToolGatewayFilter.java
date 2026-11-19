package com.ai.tool.security;

import com.ai.AiAuthority;
import com.ai.AiSettings;
import com.ai.AiTokenCodec;
import com.auth.model.AuthSessionAttributes;
import com.auth.model.LoginUser;
import com.auth.security.ApiSecurityResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** 工具入口独立安全链：只认tools用途凭证并构造内部主体。 */
public final class AiToolGatewayFilter extends OncePerRequestFilter {
    public static final String CLAIMS = "com.ai.tool.claims";
    private final AiSettings settings;
    private final AiTokenCodec codec;
    private final AiAuthority authority;
    private final ApiSecurityResponse responses;

    public AiToolGatewayFilter(AiSettings settings, AiTokenCodec codec, AiAuthority authority, ApiSecurityResponse responses) {
        this.settings = settings;
        this.codec = codec;
        this.authority = authority;
        this.responses = responses;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!settings.isEnabled()) {
            responses.write(response, 503, "AI_DISABLED");
            return;
        }
        AiTokenCodec.ToolClaims claims;
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI_INVALID_CREDENTIAL");
            }
            claims = codec.verifyTool(authorization.substring(7));
            authority.validate(claims.tenantId(), claims.userId());
        } catch (ResponseStatusException failure) {
            responses.write(response, failure.getStatusCode().value(), failure.getReason());
            return;
        } catch (RuntimeException failure) {
            responses.write(response, 503, "AI_AUTHORIZATION_UNAVAILABLE");
            return;
        }
        LoginUser principal = new LoginUser(claims.userId(), "ai-internal", null, null, true);
        request.setAttribute(AuthSessionAttributes.REQUEST_TENANT_ID, claims.tenantId());
        request.setAttribute(CLAIMS, claims);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            request.removeAttribute(CLAIMS);
            request.removeAttribute(AuthSessionAttributes.REQUEST_TENANT_ID);
        }
    }
}
