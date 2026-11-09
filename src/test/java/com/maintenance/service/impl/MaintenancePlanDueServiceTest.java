package com.maintenance.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.maintenance.MaintenancePlanPageQueryDto;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.maintenance.mapper.MaintenancePlanMapper;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 新筛选的分支和上海日期传递；已有分页及设备校验由原测试继续覆盖。 */
class MaintenancePlanDueServiceTest {
    private boolean denied;
    private int ordinary,due;
    private LocalDate requestedToday;
    private MaintenancePlanServerImpl service() {
        var access=(CurrentAccessService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CurrentAccessService.class},(p,m,a)->{
            assertEquals("requireActiveTenantId",m.getName());
            if(denied)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"成员失效");return 7L;
        });
        var mapper=(MaintenancePlanMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{MaintenancePlanMapper.class},(p,m,a)->{
            assertEquals(7L,a[1]);Page<?> page=(Page<?>)a[0];
            if(m.getName().equals("selectDuePlanPage")) {
                due++;assertTrue(((MaintenancePlanPageQueryDto)a[2]).getDueOnly());requestedToday=(LocalDate)a[3];
            } else { assertEquals("selectPlanPage",m.getName());ordinary++; }
            return new Page<MaintenancePlanVO>(page.getCurrent(),page.getSize(),0);
        });
        var factory=new DefaultListableBeanFactory();factory.registerSingleton("currentAccessService",access);factory.registerSingleton("maintenancePlanMapper",mapper);
        var processor=new AutowiredAnnotationBeanPostProcessor();processor.setBeanFactory(factory);
        var service=new MaintenancePlanServerImpl();processor.processInjection(service);return service;
    }
    @Test void true查询到期待办并传入服务端上海日期() {
        var query=new MaintenancePlanPageQueryDto();query.setDueOnly(true);query.setCurrent(-1);query.setSize(1000);
        LocalDate before=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var result=service().getPlanPage(query);
        LocalDate after=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assertTrue(requestedToday.equals(before)||requestedToday.equals(after));
        assertEquals(1,due);assertEquals(0,ordinary);assertEquals(1,result.getCurrent());assertEquals(100,result.getSize());
        assertEquals(0,result.getTotal());assertTrue(result.getRecords().isEmpty());
    }
    @Test void false和省略时仍走原分页并保留旧构造方式() {
        for(Boolean flag:Arrays.asList(false,null)) {
            var query=new MaintenancePlanPageQueryDto(1,10,null,null,null,null);query.setDueOnly(flag);
            assertNotNull(service().getPlanPage(query));
        }
        assertEquals(2,ordinary);assertEquals(0,due);assertNull(requestedToday);
    }
    @Test void 无有效成员资格不读取到期待办() {
        denied=true;var query=new MaintenancePlanPageQueryDto();query.setDueOnly(true);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service().getPlanPage(query)).getStatusCode().value());
        assertEquals(0,due);assertEquals(0,ordinary);
    }
}
