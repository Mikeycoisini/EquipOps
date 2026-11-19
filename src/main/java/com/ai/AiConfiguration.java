package com.ai;

import com.auth.security.ApiSecurityResponse;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/** AI内部回调用独立无状态安全链，不覆盖原业务。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AiSettings.class)
public class AiConfiguration {
    @Bean
    AiTokenCodec aiTokenCodec(AiSettings settings, ObjectMapper json) {
        // 关闭AI时不要求配置密钥。
        return new AiTokenCodec(settings.getSharedSecret(), json, Clock.systemUTC());
    }

    @Bean
    AiPythonClient aiPythonClient(AiSettings settings, ObjectMapper json) {
        // Python地址只来自配置。
        return new AiPythonClient(settings.getBaseUrl(), json);
    }

    @Bean
    @Order(1)
    SecurityFilterChain aiInternalSecurity(HttpSecurity http, AiSettings settings, AiTokenCodec codec,
                                          AiAuthority authority, ApiSecurityResponse responses) throws Exception {
        // 仅覆盖内部回调；工具网关由更高优先级的专用链处理。
        http.securityMatcher("/internal/ai/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(access -> access.anyRequest().authenticated())
                .addFilterBefore(new AiGatewayFilter(settings, codec, authority, responses), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
