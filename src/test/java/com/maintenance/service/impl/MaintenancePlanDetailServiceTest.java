package com.maintenance.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.maintenance.mapper.MaintenancePlanMapper;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 实际详情方法的权限、ID及不存在分支；不连接数据库。 */
class MaintenancePlanDetailServiceTest {
    private boolean denyAccess;
    private boolean missing;
    private int queries;

    private MaintenancePlanServerImpl service() {
        var access = (CurrentAccessService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CurrentAccessService.class}, (p, method, args) -> {
                    assertEquals("requireActiveTenantId", method.getName());
                    if (denyAccess) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "成员已失效");
                    return 7L;
                });
        var mapper = (MaintenancePlanMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MaintenancePlanMapper.class}, (p, method, args) -> {
                    assertEquals("selectPlanDetail", method.getName());
                    assertArrayEquals(new Object[]{7L, 100L}, args);
                    queries++;
                    if (missing) return null;
                    var plan = new MaintenancePlanVO(); plan.setId(100L); plan.setTenantId(7L);
                    plan.setEquipmentName("空压机"); plan.setAssigneeName("维修员"); plan.setEnabled(false);
                    return plan;
                });
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("currentAccessService", access); factory.registerSingleton("maintenancePlanMapper", mapper);
        var processor = new AutowiredAnnotationBeanPostProcessor(); processor.setBeanFactory(factory);
        var service = new MaintenancePlanServerImpl(); processor.processInjection(service);
        return service;
    }

    @Test void 普通有效成员可查看本企业停用计划及关联名称() {
        var plan = service().getPlanDetail(100L);
        assertEquals(100L, plan.getId()); assertEquals(7L, plan.getTenantId());
        assertEquals("空压机", plan.getEquipmentName()); assertEquals("维修员", plan.getAssigneeName());
        assertFalse(plan.getEnabled()); assertEquals(1, queries);
    }
    @Test void 空零负数ID返回400且不查询数据库() {
        var service = service();
        for (Long id : Arrays.asList(null, 0L, -1L)) {
            var error = assertThrows(ResponseStatusException.class, () -> service.getPlanDetail(id));
            assertEquals(400, error.getStatusCode().value());
        }
        assertEquals(0, queries);
    }
    @Test void 不存在或不在本企业查询结果内返回404() {
        missing = true;
        var error = assertThrows(ResponseStatusException.class, () -> service().getPlanDetail(100L));
        assertEquals(404, error.getStatusCode().value()); assertEquals(1, queries);
    }
    @Test void 无有效成员访问权限时不读取详情() {
        denyAccess = true;
        var error = assertThrows(ResponseStatusException.class, () -> service().getPlanDetail(100L));
        assertEquals(403, error.getStatusCode().value()); assertEquals(0, queries);
    }
}
