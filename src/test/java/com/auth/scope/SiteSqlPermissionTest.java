package com.auth.scope;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.config.MybatisPlusConfig;
import com.equipops.domain.entity.TenantEntity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SiteSqlPermissionTest {
    private final CurrentAccessService access = mock(CurrentAccessService.class);
    private String rewrite(String sql) { return rewrite(sql,"com.equipment.mapper.EquipmentMapper.test"); }
    private String rewrite(String sql,String mappedId) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(CurrentAccessService.class, () -> access);
            context.register(MybatisPlusConfig.class);
            context.refresh();
            var interceptors = context.getBean(MybatisPlusInterceptor.class).getInterceptors();
            assertInstanceOf(DataPermissionInterceptor.class, interceptors.get(0), "权限应先于分页生效");
            return ((DataPermissionInterceptor) interceptors.get(0)).parserSingle(sql,mappedId);
        }
    }
    private void actor(boolean admin) {
        TenantEntity tenant = new TenantEntity(); tenant.setId(71L);
        when(access.requireTenantAccess()).thenReturn(new TenantAccess(81L,tenant,91L,
                admin ? Set.of("TENANT_ADMIN") : Set.of("TECHNICIAN")));
    }
    @Test void noActorIsFailClosed() {
        assertTrue(rewrite("SELECT * FROM equipment e").contains("1 = 0"));
    }
    @Test void adminStillHasTenantBoundary() {
        actor(true);
        String sql = rewrite("SELECT * FROM equipment e WHERE e.status='RUNNING' OR e.status='NEEDS_ATTENTION'");
        assertTrue(sql.contains("e.tenant_id = 71"));
        assertTrue(sql.contains("(e.status = 'RUNNING' OR e.status = 'NEEDS_ATTENTION')"));
        assertFalse(sql.contains("tenant_member_site_scopes"));
    }
    @Test void ordinaryActorChecksFreshMembershipGrantRatherThanEmptyMeansAll() {
        actor(false);
        String sql = rewrite("SELECT COUNT(*) FROM work_orders wo");
        assertTrue(sql.contains("tenant_member_site_scopes"));
        assertTrue(sql.contains("membership_id = 91"));
        assertTrue(sql.contains("wo.site_id"));
        assertTrue(sql.contains("enabled = 1"));
    }
    @Test void leftJoinedTransactionHasOwnWarehouseAndWorkOrderProtection() {
        actor(false);
        String sql = rewrite("SELECT t.* FROM inventory_transactions t LEFT JOIN warehouses w ON w.id=t.warehouse_id");
        assertTrue(sql.contains("t.warehouse_id"));
        assertTrue(sql.contains("t.work_order_id IS NULL OR EXISTS"));
        assertTrue(sql.contains("FROM work_orders"));
    }
    @Test void joinedUpdateRootPartAndDeleteRelationHaveParentScope() {
        actor(false);
        assertTrue(rewrite("UPDATE work_order_parts wp JOIN work_orders wo ON wo.id=wp.work_order_id SET wp.returned_quantity=1 WHERE wp.id=2").contains("wp.work_order_id"));
        assertTrue(rewrite("DELETE FROM equipment_spare_parts WHERE id=2").contains("equipment_spare_parts.equipment_id"));
    }
    @Test void unionEveryBranchIsProtected() {
        actor(false);
        String sql = rewrite("SELECT * FROM (SELECT id FROM work_orders UNION ALL SELECT id FROM work_orders) d");
        assertEquals(2, sql.split("membership_id = 91",-1).length-1);
    }
    @Test void onlyExactBackgroundMapperIsExempt() {
        actor(false);
        assertFalse(rewrite("SELECT * FROM warehouses w","com.inventory.mapper.InventoryExportTaskMapper.selectExportBatch").contains("tenant_member_site_scopes"));
        assertTrue(rewrite("SELECT * FROM warehouses w","com.inventory.mapper.FakeInventoryExportTaskMapper.selectExportBatch").contains("tenant_member_site_scopes"));
    }
}
