package com.workorder.service.impl;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.workorder.WorkOrderRejectDto;
import com.equipops.domain.entity.*;
import com.equipops.domain.enums.*;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证退回权限、状态与写入失败分支；使用内存数据，不启动应用或连接数据库。 */
class WorkOrderRejectServiceTest {
    private Long userId = 1L;
    private Set<String> roles = Set.of();
    private WorkOrderEntity order = order();
    private final List<WorkOrderLogEntity> logs = new ArrayList<>();
    private int updateRows = 1;
    private int logRows = 1;
    private int updates;

    private WorkOrderEntity order() {
        WorkOrderEntity value = new WorkOrderEntity();
        value.setId(10L);
        value.setTenantId(7L);
        value.setCreatedBy(1L);
        value.setAssigneeUserId(20L);
        value.setStatus(WorkOrderStatus.AWAITING_ACCEPTANCE);
        return value;
    }

    private WorkOrderServiceImpl service() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(7L);
        CurrentAccessService access = (CurrentAccessService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{CurrentAccessService.class}, (p, m, a) -> {
                    assertEquals("requireTenantAccess", m.getName());
                    return new TenantAccess(userId, tenant, 30L, roles);
                });
        WorkOrderMapper mapper = (WorkOrderMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WorkOrderMapper.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "selectTenantAnduserId":
                            assertArrayEquals(new Object[]{7L, 10L}, a);
                            return order;
                        case "rejectWorkOrder":
                            assertArrayEquals(new Object[]{7L, 10L, userId}, a);
                            updates++;
                            if (updateRows == 1) order.setStatus(WorkOrderStatus.IN_PROGRESS);
                            return updateRows;
                        case "insertWorkLog":
                            if (logRows == 1) logs.add((WorkOrderLogEntity) a[0]);
                            return logRows;
                        case "selectOne": return order;
                        default: throw new AssertionError("非预期数据库调用：" + m.getName());
                    }
                });
        return new WorkOrderServiceImpl(mapper, null, null, null, access, null);
    }

    private WorkOrderRejectDto request(String reason) {
        return new WorkOrderRejectDto(reason);
    }

    private void fails(int status, Long id, WorkOrderRejectDto request) {
        var error = assertThrows(ResponseStatusException.class, () -> service().reject(id, request));
        assertEquals(status, error.getStatusCode().value());
        assertTrue(logs.isEmpty());
    }

    @Test void 报修人退回后返回维修中且保留负责人并记录原因和操作者() {
        var result = service().reject(10L, request("  设备仍有异响  "));
        assertNotNull(result);
        assertEquals(WorkOrderStatus.IN_PROGRESS, result.getStatus());
        assertEquals(20L, result.getAssigneeUserId());
        assertNull(result.getCompletedAt());
        assertEquals(1, logs.size());
        var log = logs.get(0);
        assertEquals(10L, log.getWorkOrderId());
        assertEquals(WorkOrderEventType.STATUS_CHANGED, log.getEventType());
        assertEquals(WorkOrderStatus.AWAITING_ACCEPTANCE, log.getFromStatus());
        assertEquals(WorkOrderStatus.IN_PROGRESS, log.getToStatus());
        assertEquals("设备仍有异响", log.getDetail());
        assertEquals(1L, log.getActorUserId());
    }

    @Test void 非报修人具有当前企业验收角色时允许退回() {
        userId = 2L;
        roles = Set.of("ACCEPTOR");
        assertEquals(WorkOrderStatus.IN_PROGRESS, service().reject(10L, request("故障未消除")).getStatus());
    }

    @Test void 维修负责人即使兼任报修人和验收人也不能自验收() {
        userId = 20L;
        order.setCreatedBy(20L);
        roles = Set.of("ACCEPTOR", "TENANT_ADMIN");
        fails(403, 10L, request("故障未消除"));
        assertEquals(0, updates);
    }

    @Test void 企业管理员不自动拥有验收权限() {
        userId = 2L;
        roles = Set.of("TENANT_ADMIN");
        fails(403, 10L, request("故障未消除"));
        assertEquals(0, updates);
    }

    @Test void 非待验收状态不能退回() {
        for (var status : List.of(WorkOrderStatus.PENDING, WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.COMPLETED)) {
            order.setStatus(status);
            fails(409, 10L, request("故障未消除"));
        }
        assertEquals(0, updates);
    }

    @Test void 非法ID和空白或超长原因拒绝写入() {
        fails(400, null, request("故障未消除"));
        fails(400, 0L, request("故障未消除"));
        fails(400, -1L, request("故障未消除"));
        fails(400, 10L, null);
        for (String reason : Arrays.asList(null, "", "  \t\n", "a".repeat(5001))) {
            fails(400, 10L, request(reason));
        }
        assertEquals(0, updates);
    }

    @Test void 最大长度的原因可以退回() {
        assertEquals(WorkOrderStatus.IN_PROGRESS,
                service().reject(10L, request("a".repeat(5000))).getStatus());
    }

    @Test void 未知或其他企业工单统一返回不存在() {
        order = null;
        fails(404, 10L, request("故障未消除"));
        assertEquals(0, updates);
    }

    @Test void 条件更新未命中时返回冲突且不记成功日志() {
        updateRows = 0;
        fails(409, 10L, request("故障未消除"));
    }

    @Test void 日志写入失败必须抛异常而不是返回成功() {
        logRows = 0;
        fails(500, 10L, request("故障未消除"));
    }

    @Test void 退回SQL限定企业原状态和操作人且推进版本() throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        String resource = "mapper/WorkOrderMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        var bound = configuration.getMappedStatement("com.workorder.mapper.WorkOrderMapper.rejectWorkOrder")
                .getBoundSql(Map.of("tenantId", 7L, "id", 10L, "actorUserId", 1L));
        assertEquals("UPDATE work_orders SET status = 'IN_PROGRESS', version = version + 1 WHERE tenant_id = ? AND id = ? "
                + "AND status = 'AWAITING_ACCEPTANCE' AND assignee_user_id != ?",
                bound.getSql().replaceAll("\\s+", " ").trim());
        assertEquals(List.of("tenantId", "id", "actorUserId"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }
}
