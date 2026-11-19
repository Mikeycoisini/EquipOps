package com.ai;

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

/** 资料回调用途的独立过滤器；工具网关有单独的用途限定过滤器。 */
public final class AiGatewayFilter extends OncePerRequestFilter {
    private final AiSettings settings;
    private final AiTokenCodec codec;
    private final AiAuthority authority;
    private final ApiSecurityResponse responses;

    public AiGatewayFilter(AiSettings settings, AiTokenCodec codec, AiAuthority authority, ApiSecurityResponse responses) {
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
        AiTokenCodec.Claims claims;
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) throw invalid();
            claims = codec.verify(authorization.substring(7));
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
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            request.removeAttribute(AuthSessionAttributes.REQUEST_TENANT_ID);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI_INVALID_CREDENTIAL");
    }
}
