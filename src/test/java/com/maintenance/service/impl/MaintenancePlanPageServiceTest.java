package com.maintenance.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.maintenance.MaintenancePlanPageQueryDto;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.enums.MaintenancePlanType;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.maintenance.mapper.MaintenancePlanMapper;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 实际Service的企业范围、校验及分页行为；仅替代数据库和身份读取。 */
class MaintenancePlanPageServiceTest {
    private boolean denyAccess;
    private boolean equipmentExists = true;
    private boolean emptyPage;
    private int pageCalls;
    private int equipmentCalls;
    private MaintenancePlanPageQueryDto filters;

    private MaintenancePlanServerImpl service() {
        var access = (CurrentAccessService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CurrentAccessService.class}, (p, method, args) -> {
                    assertEquals("requireActiveTenantId", method.getName(), "列表不要求管理员角色");
                    if (denyAccess) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "成员关系失效");
                    return 7L;
                });
        var mapper = (MaintenancePlanMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MaintenancePlanMapper.class}, (p, method, args) -> {
                    switch (method.getName()) {
                        case "selectTenantEquipment":
                            equipmentCalls++;
                            assertArrayEquals(new Object[]{7L, 20L}, args);
                            return equipmentExists ? new EquipmentEntity() : null;
                        case "selectPlanPage":
                            pageCalls++;
                            assertEquals(7L, args[1]);
                            Page<?> page = (Page<?>) args[0];
                            filters = (MaintenancePlanPageQueryDto) args[2];
                            var result = new Page<MaintenancePlanVO>(page.getCurrent(), page.getSize(), emptyPage ? 0 : 23);
                            var value = new MaintenancePlanVO(); value.setId(100L); value.setEquipmentName("空压机");
                            if (!emptyPage) result.setRecords(List.of(value));
                            return result;
                        default: throw new AssertionError("只读查询不应调用：" + method.getName());
                    }
                });
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("currentAccessService", access);
        factory.registerSingleton("maintenancePlanMapper", mapper);
        var processor = new AutowiredAnnotationBeanPostProcessor(); processor.setBeanFactory(factory);
        var service = new MaintenancePlanServerImpl(); processor.processInjection(service);
        return service;
    }

    private void rejects(int code, MaintenancePlanPageQueryDto query) {
        var error = assertThrows(ResponseStatusException.class, () -> service().getPlanPage(query));
        assertEquals(code, error.getStatusCode().value()); assertEquals(0, pageCalls);
    }

    @Test void 有效成员可查询且分页总数和全部筛选正确传递() {
        var query = new MaintenancePlanPageQueryDto(2, 5, "  巡检  ", 20L, MaintenancePlanType.INSPECTION, false);
        IPage<MaintenancePlanVO> result = service().getPlanPage(query);
        assertEquals(23, result.getTotal()); assertEquals(2, result.getCurrent()); assertEquals(5, result.getSize());
        assertEquals("空压机", result.getRecords().get(0).getEquipmentName());
        assertEquals("巡检", filters.getKeyword()); assertEquals(20L, filters.getEquipmentId());
        assertEquals(MaintenancePlanType.INSPECTION, filters.getPlanType()); assertEquals(false, filters.getEnabled());
        assertEquals(1, equipmentCalls); assertEquals(1, pageCalls);
    }
    @Test void 默认条件不检查设备且空白名称不筛选() {
        var query = new MaintenancePlanPageQueryDto(); query.setKeyword(" \t ");
        var result = service().getPlanPage(query);
        assertEquals(1, result.getCurrent()); assertEquals(10, result.getSize());
        assertNull(filters.getKeyword()); assertNull(filters.getEnabled()); assertEquals(0, equipmentCalls);
    }
    @Test void 页码下限和条数上限沿用现有规则() {
        var result = service().getPlanPage(new MaintenancePlanPageQueryDto(-1, 1000, null, null, null, null));
        assertEquals(1, result.getCurrent()); assertEquals(100, result.getSize());
    }
    @Test void 非正条数按一条处理() {
        var result = service().getPlanPage(new MaintenancePlanPageQueryDto(1, 0, null, null, null, null));
        assertEquals(1, result.getSize());
    }
    @Test void 没有匹配记录时保留真实空分页() {
        emptyPage = true;
        var result = service().getPlanPage(new MaintenancePlanPageQueryDto());
        assertEquals(0, result.getTotal()); assertTrue(result.getRecords().isEmpty());
    }
    @Test void 未知或其他企业设备返回404() {
        equipmentExists = false; var query = new MaintenancePlanPageQueryDto(); query.setEquipmentId(20L);
        rejects(404, query); assertEquals(1, equipmentCalls);
    }
    @Test void 非正设备ID拒绝且不查询设备() {
        var query = new MaintenancePlanPageQueryDto(); query.setEquipmentId(0L);
        rejects(400, query); assertEquals(0, equipmentCalls);
    }
    @Test void 成员失效时禁止读取分页和设备() {
        denyAccess = true; rejects(403, new MaintenancePlanPageQueryDto()); assertEquals(0, equipmentCalls);
    }
    @Test void 超长名称即使直接调用Service也拒绝() {
        var query = new MaintenancePlanPageQueryDto(); query.setKeyword("a".repeat(161)); rejects(400, query);
    }
}
