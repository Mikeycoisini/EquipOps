package com.inventory.mapper;

import com.EquipOpsApplication;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.inventory.InventoryExportCreateDto;
import com.equipops.domain.entity.InventoryExportTaskEntity;
import com.inventory.export.InventoryExportDownloadRecord;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实MySQL授权反例：1. 验证隔离库；2. 创建UUID对象；3. 执行真实Mapper；4. 精准逆序清理。
 * 不开启测试事务，使授权修改提交后每次Mapper读取使用新的会话，避免一级缓存或RR快照掩盖撤权。
 * 当前生产实现已存在，本类补充真实SQL证据，不声称这些用例经历过RED。
 */
@SpringBootTest(classes = EquipOpsApplication.class)
@EnabledIfEnvironmentVariable(named = "A3_MYSQL_TESTS", matches = "true")
class InventoryExportScopeIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InventoryExportTaskMapper tasks;
    @Autowired private InventoryExportDownloadMapper downloads;
    /** 后台SQL必须完全依据固定任务身份，不能依赖请求或模拟返回的授权对象。 */
    @MockitoBean private CurrentAccessService currentAccessService;
    private String tag;
    private long tenantId, userId, membershipId, siteA, siteB, warehouseA, warehouseB, partId, workOrderB;
    private long transactionA, transactionB, crossSiteTransaction;

    @BeforeEach
    void 创建隔离的企业成员厂区与流水() {
        // 1. 未确认专用隔离库前不执行任何写入。
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(database);
        assertTrue(database.matches("equipops_a1_[A-Za-z0-9_]+"), "只允许equipops_a1_*隔离库");
        // 2. UUID业务编码隔离本次对象，不读取或使用任何已有业务数据。
        tag = UUID.randomUUID().toString().replace("-", "");
        tenantId = insert("INSERT INTO tenants(tenant_code,name) VALUES(?,?)", "ex" + tag, "导出范围集成测试");
        userId = insert("INSERT INTO users(account,password_hash,display_name) VALUES(?,?,?)", "ex" + tag, "synthetic-test-only", "导出测试成员");
        membershipId = insert("INSERT INTO tenant_memberships(tenant_id,user_id) VALUES(?,?)", tenantId, userId);
        // 3. 两个启用厂区及各自仓库，初始不授予任何厂区权限。
        siteA = insert("INSERT INTO sites(tenant_id,site_code,name) VALUES(?,?,?)", tenantId, "a" + tag, "厂区甲");
        siteB = insert("INSERT INTO sites(tenant_id,site_code,name) VALUES(?,?,?)", tenantId, "b" + tag, "厂区乙");
        warehouseA = insert("INSERT INTO warehouses(tenant_id,site_id,warehouse_code,name) VALUES(?,?,?,?)", tenantId, siteA, "a" + tag, "仓库甲");
        warehouseB = insert("INSERT INTO warehouses(tenant_id,site_id,warehouse_code,name) VALUES(?,?,?,?)", tenantId, siteB, "b" + tag, "仓库乙");
        partId = insert("INSERT INTO spare_parts(tenant_id,part_code,name) VALUES(?,?,?)", tenantId, tag, "测试备件");
        // 4. 乙厂区工单与甲仓库流水形成双厂区权限反例。
        long equipmentId = insert("INSERT INTO equipment(tenant_id,site_id,asset_code,name) VALUES(?,?,?,?)", tenantId, siteB, tag, "测试设备");
        workOrderB = insert("INSERT INTO work_orders(tenant_id,site_id,order_no,title,equipment_id) VALUES(?,?,?,?,?)", tenantId, siteB, tag, "测试工单", equipmentId);
        transactionA = transaction("a", warehouseA, null);
        transactionB = transaction("b", warehouseB, null);
        crossSiteTransaction = transaction("c", warehouseA, workOrderB);
    }

    @AfterEach
    void 精准清理本次合成对象() {
        // 1. 只有本次成功创建的企业ID可进入清理，不按模糊前缀删除。
        if (tenantId != 0) {
            // 2. 按外键依赖逆序删除任务快照、任务、流水、工单、设备及库存基础对象。
            jdbc.update("DELETE FROM inventory_export_task_sites WHERE tenant_id=?", tenantId);
            jdbc.update("DELETE FROM inventory_export_tasks WHERE tenant_id=?", tenantId);
            jdbc.update("DELETE FROM inventory_transactions WHERE tenant_id=?", tenantId);
            jdbc.update("DELETE FROM work_orders WHERE tenant_id=?", tenantId);
            jdbc.update("DELETE FROM equipment WHERE tenant_id=?", tenantId);
            jdbc.update("DELETE FROM spare_parts WHERE tenant_id=?", tenantId);
            jdbc.update("DELETE FROM warehouses WHERE tenant_id=?", tenantId);
            // 3. 范围和角色关系必须先于成员与厂区删除。
            jdbc.update("DELETE FROM tenant_member_site_scopes WHERE tenant_id=?", tenantId);
            jdbc.update("DELETE FROM tenant_member_roles WHERE tenant_id=?", tenantId);
            jdbc.update("DELETE FROM tenant_memberships WHERE tenant_id=?", tenantId);
            jdbc.update("DELETE FROM sites WHERE tenant_id=?", tenantId);
            jdbc.update("DELETE FROM tenants WHERE id=?", tenantId);
        }
        // 4. 只删除本次新建账号，不修改角色字典或其他测试数据。
        if (userId != 0) jdbc.update("DELETE FROM users WHERE id=?", userId);
    }

    @Test
    void 普通成员没有隐含范围且旧任务没有快照时拒绝执行与下载() {
        // 1. 没有显式授权的普通成员不能取得厂区列表。
        var task = task();
        assertTrue(tasks.selectAuthorizedSiteIds(task).isEmpty());
        // 2. 即使随后授权，旧任务仍然不能凭空获得厂区快照。
        grant(siteA);
        assertFalse(tasks.hasExportAccess(task));
        assertEquals(0, tasks.startRunning(task));
        succeedForDownload(task);
        var file = download(task);
        assertFalse(downloads.hasTaskSites(file));
        assertFalse(downloads.canDownload(file));
        // 3. 真实快照插入后才允许按当前权限下载同一文件。
        assertEquals(1, tasks.insertTaskSites(task, List.of(siteA)));
        assertTrue(downloads.hasTaskSites(file));
        assertTrue(downloads.canDownload(file));
    }

    @Test
    void 后加厂区授权不能扩大既有任务的批次或上界() {
        // 1. 创建时只有甲厂区权限，真实快照仅包含甲。
        grant(siteA);
        var task = task();
        assertEquals(List.of(siteA), tasks.selectAuthorizedSiteIds(task));
        assertEquals(1, tasks.insertTaskSites(task, List.of(siteA)));
        assertEquals(List.of(transactionA), rowIds(task));
        // 2. 后加乙厂区授权仍不能导出乙仓库或关联乙工单的甲仓库流水。
        grant(siteB);
        assertEquals(List.of(siteA, siteB), tasks.selectAuthorizedSiteIds(task));
        assertEquals(List.of(transactionA), rowIds(task));
        assertEquals(transactionA, tasks.selectScopedUpperBound(task));
    }

    @Test
    void 关联工单与仓库必须同时位于快照和实时授权范围() {
        // 1. 两厂区均入快照且授权时，允许完整查询三条合成流水。
        grant(siteA);
        grant(siteB);
        var task = task();
        assertEquals(2, tasks.insertTaskSites(task, List.of(siteA, siteB)));
        assertEquals(List.of(transactionA, transactionB, crossSiteTransaction), rowIds(task));
        // 2. 创建筛选同样检查工单厂区，不能只检查仓库。
        var filter = new InventoryExportCreateDto();
        filter.setWarehouseId(warehouseA);
        filter.setWorkOrderId(workOrderB);
        assertTrue(tasks.hasCreateFilterAccess(task, filter));
        revoke(siteB);
        assertFalse(tasks.hasCreateFilterAccess(task, filter));
        // 3. 快照任一厂区撤权后整体停止，不静默生成部分文件。
        assertFalse(tasks.hasExportAccess(task));
        assertTrue(rowIds(task).isEmpty());
        assertEquals(0, tasks.selectScopedUpperBound(task));
    }

    @Test
    void 撤权后新查询拒绝启动发布和已成功文件下载() {
        // 1. 已授权的任务分别保留排队、运行、成功状态，覆盖三个数据库状态转换边界。
        grant(siteA);
        var queued = task();
        var running = task();
        var succeeded = task();
        for (var task : List.of(queued, running, succeeded)) tasks.insertTaskSites(task, List.of(siteA));
        assertEquals(1, tasks.startRunning(running));
        succeedForDownload(succeeded);
        var file = download(succeeded);
        assertTrue(tasks.hasExportAccess(queued));
        assertTrue(downloads.canDownload(file));
        // 2. 以已提交的独立JDBC语句撤销授权，后续Mapper读取不得复用旧权限。
        revoke(siteA);
        assertFalse(tasks.hasExportAccess(queued));
        assertEquals(0, tasks.startRunning(queued));
        assertEquals(0, tasks.publishSuccess(running, key(running), "test.xlsx", 4L, 1L));
        assertFalse(downloads.canDownload(file));
    }

    @Test
    void 管理员角色实时生效且停用厂区仍不可导出() {
        // 1. 角色字典只读，测试关系仅关联本次成员。
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE role_code='TENANT_ADMIN'", Long.class);
        assertNotNull(roleId);
        jdbc.update("INSERT INTO tenant_member_roles(tenant_id,membership_id,role_id) VALUES(?,?,?)", tenantId, membershipId, roleId);
        var task = task();
        assertEquals(List.of(siteA, siteB), tasks.selectAuthorizedSiteIds(task));
        tasks.insertTaskSites(task, List.of(siteA, siteB));
        // 2. 管理员也不能绕过厂区启用状态。
        jdbc.update("UPDATE sites SET enabled=0 WHERE tenant_id=? AND id=?", tenantId, siteB);
        assertEquals(List.of(siteA), tasks.selectAuthorizedSiteIds(task));
        assertFalse(tasks.hasExportAccess(task));
        // 3. 恢复厂区后移除管理员角色，不存在显式授权即全部失效。
        jdbc.update("UPDATE sites SET enabled=1 WHERE tenant_id=? AND id=?", tenantId, siteB);
        assertTrue(tasks.hasExportAccess(task));
        jdbc.update("DELETE FROM tenant_member_roles WHERE tenant_id=? AND membership_id=?", tenantId, membershipId);
        assertTrue(tasks.selectAuthorizedSiteIds(task).isEmpty());
        assertFalse(tasks.hasExportAccess(task));
    }

    private long insert(String sql, Object... values) {
        // 1. JDBC生成键通过同一插入语句取得，不依赖跨连接的LAST_INSERT_ID。
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        assertNotNull(keys.getKey());
        return keys.getKey().longValue();
    }

    private long transaction(String prefix, long warehouse, Long workOrder) {
        // 1. 最小合法流水依赖实际企业、仓库、备件及可选工单。
        return insert("INSERT INTO inventory_transactions(tenant_id,transaction_no,warehouse_id,spare_part_id,transaction_type,quantity,work_order_id) VALUES(?,?,?,?,'INBOUND',1,?)", tenantId, prefix + tag, warehouse, partId, workOrder);
    }

    private InventoryExportTaskEntity task() {
        // 1. 固定内部任务身份，执行器实例和令牌均为本测试生成的UUID。
        var task = InventoryExportTaskEntity.builder().tenantId(tenantId).createdBy(userId)
                .ownerInstanceId(UUID.randomUUID().toString()).runToken(UUID.randomUUID().toString())
                .upperBoundId(Long.MAX_VALUE).build();
        assertEquals(1, tasks.insertQueuedTask(task, new InventoryExportCreateDto(), tenantId, userId));
        // 2. 隔离测试不运行导出worker，延长租约避免后台维护器与慢速测试竞争。
        jdbc.update("UPDATE inventory_export_tasks SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY) WHERE id=? AND tenant_id=?", task.getId(), tenantId);
        return task;
    }

    private void grant(long site) {
        // 1. 合成授权只指向本次企业、成员、厂区和账号。
        jdbc.update("INSERT INTO tenant_member_site_scopes(tenant_id,membership_id,site_id,granted_by) VALUES(?,?,?,?)", tenantId, membershipId, site, userId);
    }

    private void revoke(long site) {
        // 1. 删除一条精确授权并立即提交，下一次Mapper查询应读取撤权后的状态。
        assertEquals(1, jdbc.update("DELETE FROM tenant_member_site_scopes WHERE tenant_id=? AND membership_id=? AND site_id=?", tenantId, membershipId, site));
    }

    private List<Long> rowIds(InventoryExportTaskEntity task) {
        // 1. 执行真实联表和权限SQL，并断言实际返回的数据ID。
        return tasks.selectExportBatch(task, 0L).stream().map(row -> row.getId()).toList();
    }

    private String key(InventoryExportTaskEntity task) {
        // 1. 使用与生产一致的任务ID和UUID存储键，不创建真实文件。
        return task.getId() + "-" + task.getRunToken() + "/inventory.xlsx";
    }

    private void succeedForDownload(InventoryExportTaskEntity task) {
        // 1. 合成成功记录只用于验证下载SQL授权，不触发文件打开或网络传输。
        assertEquals(1, jdbc.update("UPDATE inventory_export_tasks SET status='SUCCEEDED',storage_key=?,file_size=4,expires_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY) WHERE id=? AND tenant_id=?", key(task), task.getId(), tenantId));
    }

    private InventoryExportDownloadRecord download(InventoryExportTaskEntity task) {
        // 1. 下载内部记录仍通过企业、创建人和任务ID三个可信条件取得。
        var file = downloads.selectOwnDownload(tenantId, userId, task.getId());
        assertNotNull(file);
        return file;
    }
}
