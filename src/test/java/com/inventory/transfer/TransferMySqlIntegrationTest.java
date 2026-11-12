package com.inventory.transfer;

import com.audit.mapper.AuditEventMapper;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.entity.AuditEventEntity;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.enums.AuditAction;
import com.inventory.service.impl.InventoryExportServiceImpl;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "A5_MYSQL_TESTS", matches = "true")
class TransferMySqlIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired TransferService transfers;
    @MockitoBean CurrentAccessService access;
    @MockitoBean InventoryExportServiceImpl inventoryExports;
    @MockitoSpyBean AuditEventMapper auditMapper;
    private long tenant, source, target, part, user;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        // 在 Spring 创建数据源和执行迁移前拒绝默认业务库。
        String url = System.getenv("DB_URL");
        if (url == null || !url.startsWith("jdbc:mysql://")
                || !String.valueOf(URI.create(url.substring("jdbc:".length())).getPath())
                    .matches("/equipops_a1_[A-Za-z0-9_]+")) {
            throw new IllegalStateException("A5 集成测试需要显式配置隔离的 equipops_a1_ 测试库");
        }
        String namespace = "equipops:a5:test:" + UUID.randomUUID();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> Objects.requireNonNullElse(System.getenv("DB_USER"), "root"));
        registry.add("spring.flyway.password", () -> Objects.requireNonNullElse(System.getenv("DB_PASSWORD"), ""));
        registry.add("spring.session.data.redis.namespace", () -> namespace);
    }

    @BeforeEach
    void fixture() {
        assertTrue(db.queryForObject("SELECT DATABASE()", String.class).matches("equipops_a1_[A-Za-z0-9_]+"));
        String key = UUID.randomUUID().toString();
        db.update("INSERT INTO tenants(tenant_code,name) VALUES (?,'A5 transfer')", key);
        tenant = id("SELECT id FROM tenants WHERE tenant_code=?", key);
        db.update("INSERT INTO users(account,password_hash,display_name) VALUES (?,'unused-test-hash','A5 admin')", key);
        user = id("SELECT id FROM users WHERE account=?", key);
        db.update("INSERT INTO tenant_memberships(tenant_id,user_id) VALUES (?,?)", tenant, user);
        long member = id("SELECT id FROM tenant_memberships WHERE tenant_id=? AND user_id=?", tenant, user);
        db.update("""
            INSERT INTO roles(role_code,name) SELECT 'TENANT_ADMIN','企业管理员'
            WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_code='TENANT_ADMIN')
            """);
        db.update("""
            INSERT INTO tenant_member_roles(tenant_id,membership_id,role_id)
            SELECT ?,?,id FROM roles WHERE role_code='TENANT_ADMIN'
            """, tenant, member);
        db.update("INSERT INTO sites(tenant_id,site_code,name) VALUES (?,'SOURCE','源厂区'),(?,'TARGET','目标厂区')", tenant, tenant);
        db.update("""
            INSERT INTO warehouses(tenant_id,site_id,warehouse_code,name)
            SELECT tenant_id,id,site_code,name FROM sites WHERE tenant_id=?
            """, tenant);
        source = id("SELECT id FROM warehouses WHERE tenant_id=? AND warehouse_code='SOURCE'", tenant);
        target = id("SELECT id FROM warehouses WHERE tenant_id=? AND warehouse_code='TARGET'", tenant);
        db.update("INSERT INTO spare_parts(tenant_id,part_code,name) VALUES (?,'A5','调拨测试备件')", tenant);
        part = id("SELECT id FROM spare_parts WHERE tenant_id=? AND part_code='A5'", tenant);
        db.update("INSERT INTO warehouse_stock(tenant_id,warehouse_id,spare_part_id,quantity) VALUES (?,?,?,10)",
                tenant, source, part);
        var company = new TenantEntity();
        company.setId(tenant);
        company.setEnabled(true);
        when(access.requireTenantAccess()).thenReturn(new TenantAccess(user, company, member, Set.of("TENANT_ADMIN")));
    }

    @AfterEach
    void cleanup() {
        // 只清理本次测试创建的企业和用户。
        if (tenant > 0) {
            for (String table : new String[]{"audit_events", "inventory_transactions", "warehouse_transfer_operations",
                    "warehouse_transfers", "warehouse_stock", "spare_parts", "warehouses", "sites",
                    "tenant_member_roles", "tenant_memberships"}) {
                db.update("DELETE FROM " + table + " WHERE tenant_id=?", tenant);
            }
            db.update("DELETE FROM tenants WHERE id=?", tenant);
        }
        if (user > 0) db.update("DELETE FROM users WHERE id=?", user);
    }

    @Test
    void 调拨在途挂起恢复并仅完整入库一次() {
        var create = new TransferController.Create("a5-create", source, target, part, new BigDecimal("3.000"), null);
        var request = transfers.create(create);
        long transfer = Long.parseLong(request.id());
        assertEquals(10, balance(source));
        var dispatched = transfers.act(transfer, TransferService.Action.DISPATCH, command("out", 0, null));
        assertEquals(7, balance(source));
        assertEquals(3, dispatched.inTransitQuantity().intValueExact());
        var suspended = transfers.act(transfer, TransferService.Action.EXCEPTION, command("hold", 1, "待核验"));
        assertEquals(3, suspended.inTransitQuantity().intValueExact());
        transfers.act(transfer, TransferService.Action.RESUME, command("resume", 2, "数量已核实"));
        var receive = command("in", 3, null);
        var received = transfers.act(transfer, TransferService.Action.RECEIVE, receive);
        assertEquals(received, transfers.act(transfer, TransferService.Action.RECEIVE, receive));
        assertEquals(request, transfers.create(create));
        assertEquals(0, received.inTransitQuantity().intValueExact());
        assertEquals(10, balance(source) + balance(target));
        assertEquals(3, balance(target));
        assertEquals(2, transfers.detail(transfer).movements().size());
        assertEquals(5, transfers.detail(transfer).operations().size());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> transfers.act(transfer, TransferService.Action.RECEIVE, command("in", 4, null)))
                .getStatusCode().value());
    }

    @Test
    void 两个独立事务并发收货时异号只成功一次() throws Exception {
        verifyConcurrentReceipt("receive-1", "receive-2", List.of(200, 409));
    }

    @Test
    void 两个独立事务并发收货时同号重放且仅入库一次() throws Exception {
        verifyConcurrentReceipt("receive-same", "receive-same", List.of(200, 200));
    }

    private void verifyConcurrentReceipt(String firstKey, String secondKey, List<Integer> expected) throws Exception {
        long transfer = dispatch();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> receiveConcurrently(transfer, firstKey, ready, start));
            var second = pool.submit(() -> receiveConcurrently(transfer, secondKey, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(expected, results.stream().sorted().toList());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertReceivedOnce(transfer);
    }

    @Test
    void 收货最后写审计失败时余额流水状态及幂等记录一起回滚() {
        long transfer = dispatch();
        var receive = command("receive-retry", 1, null);
        var injected = new IllegalStateException("A5 injected audit failure");
        doThrow(injected).when(auditMapper).insert(argThat((AuditEventEntity event) ->
                event != null && event.getTenantId() == tenant && event.getAction() == AuditAction.TRANSFER_RECEIVED));

        assertSame(injected, assertThrows(IllegalStateException.class,
                () -> transfers.act(transfer, TransferService.Action.RECEIVE, receive)));
        var detail = transfers.detail(transfer);
        assertEquals(TransferService.Status.IN_TRANSIT, detail.transfer().status());
        assertEquals(1, detail.transfer().version());
        assertEquals(3, detail.transfer().inTransitQuantity().intValueExact());
        assertEquals(7, balance(source));
        assertEquals(0, id("SELECT COUNT(*) FROM warehouse_stock WHERE tenant_id=? AND warehouse_id=?", tenant, target));
        assertEquals(1, detail.movements().size());
        assertEquals(2, detail.operations().size());
        assertEquals(2, id("SELECT COUNT(*) FROM audit_events WHERE tenant_id=?", tenant));

        reset(auditMapper);
        transfers.act(transfer, TransferService.Action.RECEIVE, receive);
        assertReceivedOnce(transfer);
    }

    private long dispatch() {
        var transfer = transfers.create(new TransferController.Create("create", source, target, part,
                new BigDecimal("3.000"), null));
        long transferId = Long.parseLong(transfer.id());
        transfers.act(transferId, TransferService.Action.DISPATCH, command("dispatch", 0, null));
        return transferId;
    }

    private int receiveConcurrently(long transfer, String key, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发收货未开始");
        try {
            transfers.act(transfer, TransferService.Action.RECEIVE, command(key, 1, null));
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    private void assertReceivedOnce(long transfer) {
        var detail = transfers.detail(transfer);
        assertEquals(TransferService.Status.RECEIVED, detail.transfer().status());
        assertEquals(2, detail.transfer().version());
        assertEquals(0, detail.transfer().inTransitQuantity().intValueExact());
        assertEquals(7, balance(source));
        assertEquals(3, balance(target));
        assertEquals(2, detail.movements().size());
        assertEquals(3, detail.operations().size());
        assertEquals(3, id("SELECT COUNT(*) FROM audit_events WHERE tenant_id=?", tenant));
    }

    private long id(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }

    private long balance(long warehouse) {
        return id("SELECT quantity FROM warehouse_stock WHERE tenant_id=? AND warehouse_id=? AND spare_part_id=?",
                tenant, warehouse, part);
    }

    private TransferController.Command command(String key, int version, String reason) {
        return new TransferController.Command(key, version, reason);
    }
}
