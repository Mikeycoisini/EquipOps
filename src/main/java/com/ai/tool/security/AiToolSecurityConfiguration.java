package com.ai.tool.security;

import com.ai.AiAuthority;
import com.ai.AiSettings;
import com.ai.AiTokenCodec;
import com.auth.security.ApiSecurityResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

/** 只覆盖/internal/ai/tools/**的无状态安全链。 */
@Configuration
public class AiToolSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain aiToolInternalSecurity(HttpSecurity http, AiSettings settings, AiTokenCodec codec,
                                               AiAuthority authority, ApiSecurityResponse responses) throws Exception {
        http.securityMatcher("/internal/ai/tools/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(access -> access.anyRequest().authenticated())
                .addFilterBefore(new AiToolGatewayFilter(settings, codec, authority, responses),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
