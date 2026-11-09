package com.maintenance.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.maintenance.MaintenancePlanEnabledUpdateDto;
import com.maintenance.mapper.MaintenancePlanMapper;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 验证实际业务的管理员校验、状态修改及相同状态重试；不连接数据库。 */
class MaintenancePlanEnabledServiceTest {
    private Boolean current = true;
    private boolean denyAdmin;
    private int rows = 1;
    private int locks;
    private int writes;
    private Boolean written;
    private MaintenancePlanServerImpl service() {
        var access = (CurrentAccessService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CurrentAccessService.class}, (p, m, args) -> {
                    assertEquals("requireTenantAdminTenantId", m.getName());
                    if (denyAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员");
                    return 7L;
                });
        var mapper = (MaintenancePlanMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MaintenancePlanMapper.class}, (p, m, args) -> {
                    assertEquals(7L, args[0]); assertEquals(100L, args[1]);
                    if (m.getName().equals("selectPlanEnabledForUpdate")) { locks++; return current; }
                    assertEquals("updatePlanEnabled", m.getName());
                    assertEquals(1, locks); writes++; written = (Boolean) args[2]; return rows;
                });
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("currentAccessService", access); factory.registerSingleton("maintenancePlanMapper", mapper);
        var processor = new AutowiredAnnotationBeanPostProcessor(); processor.setBeanFactory(factory);
        var service = new MaintenancePlanServerImpl(); processor.processInjection(service); return service;
    }
    @Test void 管理员可停用计划() {
        service().updatePlanEnabled(100L, new MaintenancePlanEnabledUpdateDto(false));
        assertEquals(1, writes); assertEquals(false, written);
    }
    @Test void 管理员可重新启用计划() {
        current = false; service().updatePlanEnabled(100L, new MaintenancePlanEnabledUpdateDto(true));
        assertEquals(1, writes); assertEquals(true, written);
    }
    @Test void 相同状态重复设置成功且不写库() {
        for (boolean value : new boolean[]{true, false}) {
            current = value; service().updatePlanEnabled(100L, new MaintenancePlanEnabledUpdateDto(value));
        }
        assertEquals(0, writes); assertEquals(2, locks);
    }
    @Test void 非管理员禁止锁定和修改() {
        denyAdmin = true;
        var error = assertThrows(ResponseStatusException.class,
                () -> service().updatePlanEnabled(100L, new MaintenancePlanEnabledUpdateDto(false)));
        assertEquals(403, error.getStatusCode().value()); assertEquals(0, locks); assertEquals(0, writes);
    }
    @Test void 不存在或其他企业计划返回404() {
        current = null;
        var error = assertThrows(ResponseStatusException.class,
                () -> service().updatePlanEnabled(100L, new MaintenancePlanEnabledUpdateDto(false)));
        assertEquals(404, error.getStatusCode().value()); assertEquals(0, writes);
    }
    @Test void 非法ID或缺少状态拒绝查询() {
        var service = service();
        for (Long id : Arrays.asList(null, 0L, -1L)) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> service.updatePlanEnabled(id, new MaintenancePlanEnabledUpdateDto(false))).getStatusCode().value());
        }
        for (MaintenancePlanEnabledUpdateDto request : Arrays.asList(null, new MaintenancePlanEnabledUpdateDto())) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> service.updatePlanEnabled(100L, request)).getStatusCode().value());
        }
        assertEquals(0, locks); assertEquals(0, writes);
    }
    @Test void 修改未影响一行时抛异常() {
        rows = 0;
        var error = assertThrows(ResponseStatusException.class,
                () -> service().updatePlanEnabled(100L, new MaintenancePlanEnabledUpdateDto(false)));
        assertEquals(500, error.getStatusCode().value()); assertEquals(1, writes);
    }
}
