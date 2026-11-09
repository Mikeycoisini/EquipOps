package com.common.logging;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import java.net.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
class OperationsHttpTest {
 @Configuration(proxyBeanMethods=false)
 @EnableAutoConfiguration(excludeName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
 "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration","org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
 "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration","org.springframework.boot.session.autoconfigure.SessionAutoConfiguration",
 "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"})
 @Import({com.config.OperationsSecurityConfig.class,RequestLoggingConfig.class})
 static class App {}
 @Test void managementIsSeparateAndDetailsAreHidden() throws Exception {
  try(var ctx=SpringApplication.run(App.class,"--spring.profiles.active=ops","--server.port=0","--management.server.port=0",
      "--management.health.redis.enabled=false","--ops.log.directory=target/ops-test-logs")) {
   int port=Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
   int management=Integer.parseInt(ctx.getEnvironment().getProperty("local.management.port"));
   assertNotEquals(port,management);var client=HttpClient.newHttpClient();
   // 使用实际文件appender触发10MB滚动；不向控制台灌入测试负载。
   var loggerContext=(ch.qos.logback.classic.LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory();
   var file=loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE");
   assertNotNull(file);
   for(int i=0;i<3200;i++) {
    var event=new ch.qos.logback.classic.spi.LoggingEvent("test",loggerContext.getLogger("rotation"),
       ch.qos.logback.classic.Level.INFO,"x".repeat(4096),null,null);
    file.doAppend(event);
   }
   try(var paths=java.nio.file.Files.list(java.nio.file.Path.of("target/ops-test-logs/archive"))) {
    assertTrue(paths.anyMatch(p->p.toString().endsWith(".gz")));
   }

   var health=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management+"/actuator/health")).build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,health.statusCode());assertTrue(health.body().contains("\"status\":\"UP\""));assertFalse(health.body().contains("components"));assertFalse(health.body().contains("details"));
   for(String path:new String[]{"/actuator/env","/actuator/health"}) {
    int target=path.endsWith("env")?management:port;
    var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+target+path)).build(),HttpResponse.BodyHandlers.ofString());
    assertNotEquals(200,r.statusCode());
   }
  }
 }
}
