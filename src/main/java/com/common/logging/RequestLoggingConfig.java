package com.common.logging;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
@Configuration
public class RequestLoggingConfig {
 @Bean FilterRegistrationBean<RequestCorrelationFilter> requestLoggingFilter() {
  var registration=new FilterRegistrationBean<>(new RequestCorrelationFilter());
  registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
  registration.setDispatcherTypes(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.ERROR);
  registration.setAsyncSupported(true);
  return registration;
 }
}
