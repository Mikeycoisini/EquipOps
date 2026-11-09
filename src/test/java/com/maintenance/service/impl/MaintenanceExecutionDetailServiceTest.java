package com.maintenance.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import com.maintenance.mapper.MaintenancePlanMapper;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 真实详情方法的有效成员、企业范围和不存在分支；不连接数据库。 */
class MaintenanceExecutionDetailServiceTest {
    private boolean denied,missing;
    private int queries;
    private MaintenancePlanServerImpl service() {
        var access=(CurrentAccessService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CurrentAccessService.class},(p,m,a)->{
            assertEquals("requireActiveTenantId",m.getName());
            if(denied)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"成员无效");return 7L;
        });
        var mapper=(MaintenancePlanMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{MaintenancePlanMapper.class},(p,m,a)->{
            assertEquals("selectExecutionDetail",m.getName(),"详情不查询当前计划或人员状态");assertArrayEquals(new Object[]{7L,500L},a);queries++;
            if(missing)return null;
            var result=new MaintenanceExecutionVO();result.setId(500L);result.setPlanId(100L);result.setTenantId(7L);
            result.setPlanName("登记时计划");result.setEquipmentName("登记时设备");result.setExecutedByName("原执行人");return result;
        });
        var factory=new DefaultListableBeanFactory();factory.registerSingleton("currentAccessService",access);factory.registerSingleton("maintenancePlanMapper",mapper);
        var processor=new AutowiredAnnotationBeanPostProcessor();processor.setBeanFactory(factory);
        var service=new MaintenancePlanServerImpl();processor.processInjection(service);return service;
    }
    @Test void 普通有效成员按执行记录ID获取本企业历史快照() {
        var result=service().getExecutionDetail(500L);assertEquals(500L,result.getId());assertEquals(100L,result.getPlanId());
        assertEquals(7L,result.getTenantId());assertEquals("登记时计划",result.getPlanName());
        assertEquals("登记时设备",result.getEquipmentName());assertEquals("原执行人",result.getExecutedByName());assertEquals(1,queries);
    }
    @Test void 空零负数ID不查询数据库() {
        var service=service();for(Long id:Arrays.asList(null,0L,-1L))assertEquals(400,assertThrows(ResponseStatusException.class,()->service.getExecutionDetail(id)).getStatusCode().value());
        assertEquals(0,queries);
    }
    @Test void 不存在或跨企业记录返回404() {
        missing=true;assertEquals(404,assertThrows(ResponseStatusException.class,()->service().getExecutionDetail(500L)).getStatusCode().value());assertEquals(1,queries);
    }
    @Test void 成员失效禁止查询执行记录() {
        denied=true;assertEquals(403,assertThrows(ResponseStatusException.class,()->service().getExecutionDetail(500L)).getStatusCode().value());assertEquals(0,queries);
    }
}
