package com.maintenance.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.maintenance.MaintenanceExecutionPageQueryDto;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.maintenance.mapper.MaintenancePlanMapper;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 测试实际只读业务的企业范围与分页，数据库由内存代理替代。 */
class MaintenanceExecutionPageServiceTest {
    private boolean denied,missing,empty;
    private int planReads,pageReads;
    private MaintenancePlanServerImpl service() {
        var access=(CurrentAccessService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CurrentAccessService.class},(p,m,a)->{
            assertEquals("requireActiveTenantId",m.getName(),"历史列表不要求管理员角色");
            if(denied)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"成员无效"); return 7L;
        });
        var mapper=(MaintenancePlanMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{MaintenancePlanMapper.class},(p,m,a)->{
            switch(m.getName()) {
                case "selectPlanDetail":
                    assertArrayEquals(new Object[]{7L,100L},a); planReads++;
                    if(missing)return null;
                    var plan=new MaintenancePlanVO(); plan.setEnabled(false); plan.setName("修改后的名称"); return plan;
                case "selectExecutionPage":
                    assertEquals(7L,a[1]); assertEquals(100L,a[2]); pageReads++;
                    Page<?> page=(Page<?>)a[0];
                    var result=new Page<MaintenanceExecutionVO>(page.getCurrent(),page.getSize(),empty?0:23);
                    if(!empty) {
                        var history=new MaintenanceExecutionVO(); history.setId(500L); history.setPlanName("登记时名称");
                        history.setEquipmentName("登记时设备"); history.setExecutedByName("原执行人"); result.setRecords(List.of(history));
                    }
                    return result;
                default:throw new AssertionError("历史查询不应写入或加锁："+m.getName());
            }
        });
        var factory=new DefaultListableBeanFactory();factory.registerSingleton("currentAccessService",access);factory.registerSingleton("maintenancePlanMapper",mapper);
        var processor=new AutowiredAnnotationBeanPostProcessor();processor.setBeanFactory(factory);
        var service=new MaintenancePlanServerImpl();processor.processInjection(service);return service;
    }
    @Test void 有效成员可查看停用计划历史且使用登记时名称() {
        var result=service().getExecutionPage(100L,new MaintenanceExecutionPageQueryDto(2,5));
        assertEquals(23,result.getTotal());assertEquals(2,result.getCurrent());assertEquals(5,result.getSize());
        assertEquals("登记时名称",result.getRecords().get(0).getPlanName());
        assertEquals("登记时设备",result.getRecords().get(0).getEquipmentName());
        assertEquals("原执行人",result.getRecords().get(0).getExecutedByName());assertEquals(1,pageReads);
    }
    @Test void 没有记录返回默认空分页() {
        empty=true;var result=service().getExecutionPage(100L,new MaintenanceExecutionPageQueryDto());
        assertEquals(1,result.getCurrent());assertEquals(10,result.getSize());assertEquals(0,result.getTotal());assertTrue(result.getRecords().isEmpty());
    }
    @Test void 页码和条数沿用现有下限及上限() {
        var result=service().getExecutionPage(100L,new MaintenanceExecutionPageQueryDto(-1,1000));
        assertEquals(1,result.getCurrent());assertEquals(100,result.getSize());
    }
    @Test void 非正条数按一条处理() {assertEquals(1,service().getExecutionPage(100L,new MaintenanceExecutionPageQueryDto(1,0)).getSize());}
    @Test void 不存在或跨企业计划返回404且不查询执行历史() {
        missing=true;assertEquals(404,assertThrows(ResponseStatusException.class,()->service().getExecutionPage(100L,new MaintenanceExecutionPageQueryDto())).getStatusCode().value());assertEquals(0,pageReads);
    }
    @Test void 非法计划ID不访问数据库() {
        var service=service();for(Long id:Arrays.asList(null,0L,-1L)) {
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.getExecutionPage(id,new MaintenanceExecutionPageQueryDto())).getStatusCode().value());
        }
        assertEquals(0,planReads);assertEquals(0,pageReads);
    }
    @Test void 成员失效不读取计划或历史() {
        denied=true;assertEquals(403,assertThrows(ResponseStatusException.class,()->service().getExecutionPage(100L,new MaintenanceExecutionPageQueryDto())).getStatusCode().value());assertEquals(0,planReads);assertEquals(0,pageReads);
    }
}
