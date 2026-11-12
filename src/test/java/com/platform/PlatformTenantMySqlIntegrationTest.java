package com.platform;

import com.auth.model.LoginUser;
import com.auth.model.AuthSessionAttributes;
import com.auth.service.CurrentAccessService;
import com.inventory.service.impl.InventoryExportServiceImpl;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "A6_MYSQL_TESTS", matches = "true")
class PlatformTenantMySqlIntegrationTest {
    @Autowired PlatformTenantService service;
    @Autowired CurrentAccessService access;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean InventoryExportServiceImpl inventoryExports;

    private final String key = UUID.randomUUID().toString();
    private long operator;
    private long admin;
    private long tenant;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv("DB_URL");
        if (url == null || !url.startsWith("jdbc:mysql://")
                || !String.valueOf(URI.create(url.substring("jdbc:".length())).getPath())
                    .matches("/equipops_a1_[A-Za-z0-9_]+")) {
            throw new IllegalStateException("A6 集成测试需要显式配置隔离的 equipops_a1_ 测试库");
        }
        String namespace = "equipops:a6:test:" + UUID.randomUUID();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> Objects.requireNonNullElse(System.getenv("DB_USER"), "root"));
        registry.add("spring.flyway.password", () -> Objects.requireNonNullElse(System.getenv("DB_PASSWORD"), ""));
        registry.add("spring.session.data.redis.namespace", () -> namespace);
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (tenant != 0) {
            jdbc.update("DELETE FROM platform_tenant_actions WHERE tenant_id=?", tenant);
            jdbc.update("DELETE FROM audit_events WHERE actor_type='PLATFORM' AND object_id=?", tenant);
            jdbc.update("DELETE FROM tenant_member_roles WHERE tenant_id=?", tenant);
            jdbc.update("DELETE FROM tenant_memberships WHERE tenant_id=?", tenant);
            jdbc.update("DELETE FROM tenants WHERE id=?", tenant);
        }
        if (operator != 0) jdbc.update("DELETE FROM platform_operator_roles WHERE user_id=?", operator);
        jdbc.update("DELETE FROM users WHERE account IN (?,?)", "op-" + key, "admin-" + key);
    }

    @Test
    void 平台授权独立且暂停和恢复实时影响已有会话() {
        open();
        assertEquals(403, status(() -> access.validateTenantSelection(tenant)));
        login(admin);
        assertEquals(403, status(service::operator));
        var existingRequest = new MockHttpServletRequest();
        existingRequest.getSession().setAttribute(AuthSessionAttributes.ACTIVE_TENANT_ID, tenant);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(existingRequest));
        assertEquals(tenant, access.requireTenantAdminTenantId());

        login(operator);
        var suspended = service.change(tenant, command(0, "计划停用"), PlatformTenantService.State.SUSPENDED);
        assertEquals(1, suspended.version());
        assertEquals(409, status(() -> service.change(tenant, command(0, "过期"), PlatformTenantService.State.ACTIVE)));
        login(admin);
        assertEquals(403, status(access::requireTenantAccess));
        assertEquals(403, status(() -> service.actions(tenant, 1, 10)));

        jdbc.update("UPDATE tenant_memberships SET enabled=0 WHERE tenant_id=? AND user_id=?", tenant, admin);
        login(operator);
        var resumed = service.change(tenant, command(1, "恢复服务"), PlatformTenantService.State.ACTIVE);
        assertEquals(2, resumed.version());
        assertEquals(List.of("TENANT_RESUMED", "TENANT_SUSPENDED", "TENANT_OPENED"),
                service.actions(tenant, 1, 10).stream().map(PlatformTenantService.Action::action).toList());
        assertEquals(3, count("SELECT COUNT(*) FROM audit_events WHERE object_id=? AND actor_type='PLATFORM' AND tenant_id IS NULL"));
        login(admin);
        assertEquals(403, status(access::requireTenantAccess));
        login(operator);
        jdbc.update("UPDATE platform_operator_roles SET enabled=0 WHERE user_id=?", operator);
        assertEquals(403, status(() -> service.detail(tenant)));
    }

    @Test
    void 动作记录写入失败时企业状态和平台审计回滚() {
        open();
        // 直接调用服务，让数据库拒绝超长原因，故障发生在状态和审计写入之后。
        assertThrows(DataIntegrityViolationException.class,
                () -> service.change(tenant, command(0, "x".repeat(501)), PlatformTenantService.State.SUSPENDED));
        assertEquals(PlatformTenantService.State.ACTIVE, service.detail(tenant).state());
        assertEquals(0, service.detail(tenant).version());
        assertEquals(1, service.actions(tenant, 1, 10).size());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE object_id=? AND actor_type='PLATFORM'"));
    }

    private void open() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("equipops_a1_"));
        operator = user("op-" + key);
        admin = user("admin-" + key);
        jdbc.update("INSERT INTO platform_operator_roles(user_id,role_code) VALUES (?,'PLATFORM_OPERATOR')", operator);
        login(operator);
        var created = service.open(new PlatformTenantController.Open(key, "A6 验证企业", "admin-" + key, "验收开通"));
        tenant = Long.parseLong(created.id());
        assertEquals(PlatformTenantService.State.ACTIVE, created.state());
    }

    private long user(String account) {
        jdbc.update("INSERT INTO users(account,password_hash,display_name) VALUES (?,'unused-test-hash','A6 fixture')", account);
        return jdbc.queryForObject("SELECT id FROM users WHERE account=?", Long.class, account);
    }

    private void login(long id) {
        var principal = new LoginUser(id, "fixture", null, "A6", true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, tenant);
    }

    private static PlatformTenantController.Change command(long version, String reason) {
        return new PlatformTenantController.Change(version, reason);
    }

    private static int status(Runnable request) {
        return assertThrows(ResponseStatusException.class, request::run).getStatusCode().value();
    }
}
