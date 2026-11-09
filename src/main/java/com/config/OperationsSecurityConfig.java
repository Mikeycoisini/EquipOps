package com.config;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
/** 即使以后误暴露其他管理端点，也拒绝读取。网络绑定由 ops 配置控制。 */
@Configuration
@Profile("ops")
public class OperationsSecurityConfig {
 @Bean @Order(0)
 SecurityFilterChain operationsSecurity(HttpSecurity http) throws Exception {
  http.securityMatcher(EndpointRequest.toAnyEndpoint())
      .authorizeHttpRequests(a->a.requestMatchers(EndpointRequest.to("health")).permitAll().anyRequest().denyAll());
  return http.build();
 }
}
