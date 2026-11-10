package com.workorder.mapper;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipment.mapper.EquipmentMapper;
import com.equipops.domain.dto.workorder.WorkOrderDispatchDto;
import com.equipops.domain.dto.workorder.WorkOrderPageQueryDto;
import com.equipops.domain.dto.workorder.RepairNoteCreateDto;
import com.equipops.domain.entity.UserEntity;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.entity.WorkOrderLogEntity;
import com.equipops.domain.enums.WorkOrderEventType;
import com.equipops.domain.enums.WorkOrderStatus;
import com.tenant.mapper.TenantMembershipMapper;
import com.workorder.service.impl.WorkOrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 使用接口代理验证业务流程，不连接数据库，不依赖动态挂载插件。 */
class WorkOrderServiceXmlTest {
    private final WorkOrderEntity order = new WorkOrderEntity();
    private final List<WorkOrderLogEntity> logs = new ArrayList<>();
    private int assignedRows = 1;
    private boolean targetAllowed = true;
    private int pageCalls;
    private int startRows = 1;
    private int startCalls;

    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, handler));
    }

    private WorkOrderServiceImpl service() {
        order.setId(10L);
        order.setTenantId(7L);
        order.setStatus(WorkOrderStatus.PENDING);
        WorkOrderMapper orders = proxy(WorkOrderMapper.class, (p, method, args) -> {
            switch (method.getName()) {
                case "selectOne": return order;
                case "startRepair":
                    startCalls++;
                    assertArrayEquals(new Object[]{7L, 10L, 1L}, args);
                    if (startRows == 1) order.setStatus(WorkOrderStatus.IN_PROGRESS);
                    return startRows;
                case "selectTenantAnduserId":
                    assertArrayEquals(new Object[]{7L, 10L}, args);
                    return order;
                case "insertWorkLog":
                    WorkOrderLogEntity log = (WorkOrderLogEntity) args[0];
                    log.setId(55L);
                    logs.add(log);
                    return 1;
                case "assignWorkOrder":
                    assertArrayEquals(new Object[]{7L, 10L, 20L}, args);
                    return assignedRows;
                case "selectWorkOrderPage":
                    pageCalls++;
                    Page<?> requested = (Page<?>) args[0];
                    assertEquals(1, requested.getCurrent());
                    assertEquals(100, requested.getSize());
                    assertEquals(7L, args[1]);
                    assertEquals("test", args[3]);
                    Page<WorkOrderEntity> page = new Page<>(1, 100, 1);
                    page.setRecords(List.of(order));
                    return page;
                default: throw new AssertionError("未预期的工单访问：" + method.getName());
            }
        });
        WorkOrderLogMapper logMapper = proxy(WorkOrderLogMapper.class, (p, method, args) -> {
            if (method.getName().equals("selectById")) {
                assertEquals(55L, args[0]);
                return logs.get(0);
            }
            if (method.getName().equals("insert")) {
                logs.add((WorkOrderLogEntity) args[0]);
                return 1;
            }
            assertEquals("selectByWorkOrderId", method.getName());
            assertArrayEquals(new Object[]{7L, 10L}, args);
            return logs;
        });
        CurrentAccessService access = proxy(CurrentAccessService.class, (p, method, args) -> {
            if (method.getName().equals("requireEnabledUserId")) return 1L;
            return 7L;
        });
        UserMapper users = proxy(UserMapper.class, (p, method, args) -> {
            UserEntity user = new UserEntity();
            user.setId(20L);
            user.setEnabled(true);
            return user;
        });
        TenantMembershipMapper memberships = proxy(TenantMembershipMapper.class, (p, method, args) -> 1L);
        EquipmentMapper equipment = proxy(EquipmentMapper.class, (p, method, args) -> {
            throw new AssertionError("派单不应修改设备");
        });
        var service = new WorkOrderServiceImpl(orders, logMapper, users, equipment, access, memberships);
        var qualification = org.mockito.Mockito.mock(com.workorder.service.impl.WorkOrderAssignmentAccess.class);
        if (!targetAllowed) org.mockito.Mockito.doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "无个人厂区授权"))
                .when(qualification).requireTarget(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"assignmentAccess",qualification);
        return service;
    }

    private WorkOrderDispatchDto dispatchRequest() {
        WorkOrderDispatchDto request = new WorkOrderDispatchDto();
        request.setAssigneeUserId(20L);
        request.setActorUserId(999L); // 业务仍使用真实登录用户，而不是客户端操作人。
        return request;
    }

    @Test
    void 原派单不能绕过目标个人厂区资格() {
        targetAllowed=false;
        var error=assertThrows(ResponseStatusException.class,()->service().dispatch(10L,dispatchRequest()));
        assertEquals(400,error.getStatusCode().value());assertTrue(logs.isEmpty());
    }

    @Test
    void 派单影响零行返回冲突且不记录成功日志() {
        assignedRows = 0;
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().dispatch(10L, dispatchRequest()));
        assertEquals(409, error.getStatusCode().value());
        assertTrue(logs.isEmpty());
    }

    @Test
    void 派单成功保留原状态并记录真实操作人() {
        var vo = service().dispatch(10L, dispatchRequest());
        assertEquals(20L, vo.getAssigneeUserId());
        assertEquals(WorkOrderStatus.PENDING, vo.getStatus());
        assertEquals(1, logs.size());
        assertEquals(WorkOrderEventType.ASSIGNED, logs.get(0).getEventType());
        assertEquals(1L, logs.get(0).getActorUserId());
        assertEquals(1, vo.getTimeline().size());
    }

    @Test
    void 开始维修保留远端状态更新和操作人日志() {
        var service = service();
        order.setAssigneeUserId(1L);
        var result = service.startRepair(10L);
        assertEquals(1, startCalls);
        assertEquals(WorkOrderStatus.IN_PROGRESS, result.getStatus());
        assertEquals(1, result.getTimeline().size());
        assertEquals(WorkOrderEventType.STATUS_CHANGED, logs.get(0).getEventType());
        assertEquals(WorkOrderStatus.PENDING, logs.get(0).getFromStatus());
        assertEquals(WorkOrderStatus.IN_PROGRESS, logs.get(0).getToStatus());
        assertEquals(1L, logs.get(0).getActorUserId());
    }

    @Test
    void 开始维修条件更新失败时不记录成功日志() {
        var service = service();
        order.setAssigneeUserId(1L);
        startRows = 0;
        var error = assertThrows(ResponseStatusException.class, () -> service.startRepair(10L));
        assertEquals(409, error.getStatusCode().value());
        assertTrue(logs.isEmpty());
    }

    @Test
    void 开始维修先校验状态派单和当前负责人() {
        var service = service();
        order.setStatus(WorkOrderStatus.IN_PROGRESS);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.startRepair(10L)).getStatusCode().value());
        order.setStatus(WorkOrderStatus.PENDING);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.startRepair(10L)).getStatusCode().value());
        order.setAssigneeUserId(20L);
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.startRepair(10L)).getStatusCode().value());
        assertEquals(0, startCalls);
        assertTrue(logs.isEmpty());
    }

    @Test
    void 合并后保留本地追加维修记录流程() {
        var service = service();
        order.setAssigneeUserId(1L);
        order.setStatus(WorkOrderStatus.IN_PROGRESS);
        RepairNoteCreateDto request = new RepairNoteCreateDto();
        request.setDetail(" 更换轴承 ");
        var result = service.addRepairNoteLog(10L, request);
        assertEquals(55L, result.getId());
        assertEquals("更换轴承", result.getDetail());
        assertEquals(WorkOrderEventType.COMMENT, result.getEventType());
        assertEquals(1L, result.getActorUserId());
        assertEquals(WorkOrderStatus.IN_PROGRESS, order.getStatus());
    }

    @Test
    void 分页继续修正参数并且不查询日志() {
        WorkOrderPageQueryDto query = new WorkOrderPageQueryDto();
        query.setCurrent(0);
        query.setSize(200);
        query.setKeyword(" test ");
        var page = service().getWorkOrderPage(query);
        assertEquals(1, pageCalls);
        assertEquals(1, page.getTotal());
        assertEquals(10L, page.getRecords().get(0).getId());
        assertEquals(List.of(), page.getRecords().get(0).getTimeline());
        assertTrue(logs.isEmpty());
        assertEquals(" test ", query.getKeyword());
    }
}
