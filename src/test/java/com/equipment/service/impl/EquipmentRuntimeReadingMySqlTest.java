package com.equipment.service.impl;

import com.EquipOpsApplication;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.entity.TenantEntity;
import com.equipment.service.EquipmentService;
import com.equipops.domain.entity.WorkOrderLogEntity;
import com.workorder.mapper.WorkOrderLogMapper;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = EquipOpsApplication.class)
@EnabledIfEnvironmentVariable(named = "A7_MYSQL_TESTS", matches = "true")
class EquipmentRuntimeReadingMySqlTest {
    @Autowired JdbcTemplate db;
    @Autowired EquipmentRuntimeReadingService service;
    @Autowired EquipmentService equipmentService;
    @Autowired EquipmentRuntimeMaintenanceService maintenance;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CurrentAccessService access;
    @MockitoSpyBean WorkOrderLogMapper orderLogs;

    @Test
    void duplicateConcurrentReadingsAreSerializedAndUnassignedSiteIsHidden() throws Exception {
        assertTrue(db.queryForObject("SELECT DATABASE()", String.class).matches("equipops_a7_[A-Za-z0-9_]+"));
        String code = UUID.randomUUID().toString().replace("-", "");
        db.update("INSERT INTO tenants(tenant_code,name) VALUES(?, 'A7 tenant')", code);
        long tenant = id("SELECT id FROM tenants WHERE tenant_code=?", code);
        db.update("INSERT INTO users(account,password_hash,display_name) VALUES(?,'unused','A7 tester')", code);
        long user = id("SELECT id FROM users WHERE account=?", code);
        try {
            db.update("INSERT INTO tenant_memberships(tenant_id,user_id) VALUES(?,?)", tenant, user);
            long member = id("SELECT id FROM tenant_memberships WHERE tenant_id=?", tenant);
            db.update("INSERT INTO sites(tenant_id,site_code,name,is_default) VALUES(?,'DEFAULT','A7 site',1)", tenant);
            long site = id("SELECT id FROM sites WHERE tenant_id=?", tenant);
            db.update("INSERT INTO equipment(tenant_id,site_id,asset_code,name) VALUES(?,?,?,'A7 equipment')",
                    tenant, site, code);
            long equipment = id("SELECT id FROM equipment WHERE tenant_id=?", tenant);
            var company = new TenantEntity();
            company.setId(tenant);
            company.setEnabled(true);
            when(access.requireTenantAccess()).thenReturn(new TenantAccess(user, company, member, Set.of()));
            when(access.requireActiveTenantId()).thenReturn(tenant);
            when(access.requireEnabledUserId()).thenReturn(user);

            var hidden = assertThrows(ResponseStatusException.class, () -> service.recent(equipment));
            assertEquals(404, hidden.getStatusCode().value());
            db.update("""
                    INSERT INTO tenant_member_site_scopes(tenant_id,membership_id,site_id,granted_by) VALUES(?,?,?,?)
                    """, tenant, member, site, user);
            assertTrue(service.recent(equipment).isEmpty());
            assertEquals(null, maintenance.current(equipment).getThresholdHours());
            when(access.requireTenantAdminTenantId()).thenReturn(tenant);
            var forbidden = assertThrows(ResponseStatusException.class,
                    () -> maintenance.configure(equipment, new BigDecimal("5.50"), 0));
            assertEquals(403, forbidden.getStatusCode().value());
            db.update("""
                    INSERT INTO roles(role_code,name)
                    SELECT 'TENANT_ADMIN','企业管理员' WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_code='TENANT_ADMIN')
                    """);
            db.update("""
                    INSERT INTO tenant_member_roles(tenant_id,membership_id,role_id)
                    SELECT ?,?,id FROM roles WHERE role_code='TENANT_ADMIN'
                    """, tenant, member);
            when(access.requireTenantAccess()).thenReturn(new TenantAccess(user, company, member, Set.of("TENANT_ADMIN")));
            assertEquals(new BigDecimal("5.50"), maintenance.configure(equipment, new BigDecimal("5.5"), 0).getThresholdHours());
            when(access.requireTenantAccess()).thenReturn(new TenantAccess(user, company, member, Set.of()));
            var transaction = new TransactionTemplate(transactions);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            transaction.executeWithoutResult(status -> {
                assertTrue(service.record(equipment, BigDecimal.ONE).getId() > 0);
                assertEquals(null, maintenance.current(equipment).getWorkOrderId());
                assertTrue(service.record(equipment, new BigDecimal("5.50")).getId() > 0);
                assertTrue(maintenance.current(equipment).getWorkOrderId() > 0);
                status.setRollbackOnly();
            });
            assertTrue(service.recent(equipment).isEmpty());
            assertEquals(0L, id("SELECT COUNT(*) FROM work_orders WHERE tenant_id=?", tenant));
            assertEquals(null, maintenance.current(equipment).getWorkOrderId());
            doReturn(0).when(orderLogs).insert(any(WorkOrderLogEntity.class));
            try {
                assertThrows(IllegalArgumentException.class, () -> service.record(equipment, new BigDecimal("5.50")));
            } finally {
                reset(orderLogs);
            }
            assertTrue(service.recent(equipment).isEmpty());
            assertEquals(0L, id("SELECT COUNT(*) FROM work_orders WHERE tenant_id=?", tenant));
            assertEquals(null, maintenance.current(equipment).getWorkOrderId());
            var start = new CountDownLatch(1);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var first = workers.submit(() -> { start.await(); return service.record(equipment, new BigDecimal("5.50")); });
                var second = workers.submit(() -> { start.await(); return service.record(equipment, new BigDecimal("5.5")); });
                start.countDown();
                assertEquals(first.get(15, TimeUnit.SECONDS).getId(), second.get(15, TimeUnit.SECONDS).getId());
            } finally {
                workers.shutdownNow();
            }

            var conflict = assertThrows(ResponseStatusException.class, () -> service.record(equipment, BigDecimal.ZERO));
            assertEquals(409, conflict.getStatusCode().value());
            assertEquals(new BigDecimal("5.50"), service.recent(equipment).get(0).getHours());
            assertEquals(1L, id("SELECT COUNT(*) FROM equipment_runtime_readings WHERE tenant_id=?", tenant));

            Long orderId = maintenance.current(equipment).getWorkOrderId();
            assertTrue(orderId > 0);
            assertEquals(1L, id("SELECT COUNT(*) FROM work_orders WHERE tenant_id=?", tenant));
            assertEquals("RUNTIME_MAINTENANCE", db.queryForObject("SELECT source FROM work_orders WHERE id=?", String.class, orderId));
            assertEquals("PENDING", db.queryForObject("SELECT status FROM work_orders WHERE id=?", String.class, orderId));
            assertEquals(1L, id("SELECT COUNT(*) FROM work_order_logs WHERE work_order_id=? AND event_type='CREATED'", orderId));
            when(access.requireTenantAccess()).thenReturn(new TenantAccess(user, company, member, Set.of("TENANT_ADMIN")));
            assertEquals(orderId, maintenance.configure(equipment, new BigDecimal("5.50"), 0).getWorkOrderId());
            assertThrows(ResponseStatusException.class, () -> maintenance.configure(equipment, BigDecimal.ONE, 1));
            assertEquals(null, maintenance.configure(equipment, new BigDecimal("10"), 1).getWorkOrderId());
            var stale = assertThrows(ResponseStatusException.class,
                    () -> maintenance.configure(equipment, new BigDecimal("7"), 1));
            assertEquals(409, stale.getStatusCode().value());
            assertEquals(new BigDecimal("10.00"), maintenance.current(equipment).getThresholdHours());
            service.record(equipment, new BigDecimal("12"));
            assertEquals(2L, id("SELECT COUNT(*) FROM work_orders WHERE tenant_id=?", tenant));
            assertTrue(!orderId.equals(maintenance.current(equipment).getWorkOrderId()));

            db.update("INSERT INTO equipment(tenant_id,site_id,asset_code,name,status) VALUES(?,?,?,'A7 second device','STOPPED')",
                    tenant, site, code + "b");
            long secondEquipment = id("SELECT id FROM equipment WHERE asset_code=?", code + "b");
            service.record(secondEquipment, new BigDecimal("5"));
            var deletion = assertThrows(ResponseStatusException.class, () -> equipmentService.deleteEquipment(secondEquipment));
            assertEquals(409, deletion.getStatusCode().value());
            assertEquals("设备已有运行小时读数，不能删除", deletion.getReason());
            assertTrue(maintenance.configure(secondEquipment, new BigDecimal("4"), 0).getWorkOrderId() > 0);
            assertEquals(3L, id("SELECT COUNT(*) FROM work_orders WHERE tenant_id=?", tenant));

            var otherTenant = new TenantEntity();
            otherTenant.setId(tenant + 1);
            when(access.requireTenantAccess()).thenReturn(new TenantAccess(user, otherTenant, member, Set.of("TENANT_ADMIN")));
            when(access.requireActiveTenantId()).thenReturn(otherTenant.getId());
            var crossTenant = assertThrows(ResponseStatusException.class, () -> service.recent(equipment));
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> maintenance.current(equipment)).getStatusCode().value());
            assertEquals(404, crossTenant.getStatusCode().value());
        } finally {
            db.update("DELETE FROM equipment_runtime_maintenance WHERE tenant_id=?", tenant);
            db.update("DELETE FROM work_orders WHERE tenant_id=?", tenant);
            db.update("DELETE FROM tenant_member_roles WHERE tenant_id=?", tenant);
            db.update("DELETE FROM equipment_runtime_readings WHERE tenant_id=?", tenant);
            db.update("DELETE FROM equipment WHERE tenant_id=?", tenant);
            db.update("DELETE FROM tenant_member_site_scopes WHERE tenant_id=?", tenant);
            db.update("DELETE FROM sites WHERE tenant_id=?", tenant);
            db.update("DELETE FROM tenant_memberships WHERE tenant_id=?", tenant);
            db.update("DELETE FROM users WHERE id=?", user);
            db.update("DELETE FROM tenants WHERE id=?", tenant);
        }
    }

    private long id(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }
}
