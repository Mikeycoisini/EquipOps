package com.maintenance.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.common.response.BusinessExceptionHandler;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import com.maintenance.service.MaintenancePlanServer;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 执行历史分页HTTP契约，不加载安全链或数据库。 */
class MaintenanceExecutionPageControllerTest {
    private org.springframework.test.web.servlet.MockMvc mvc(long current,long size) {
        var service=(MaintenancePlanServer)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{MaintenancePlanServer.class},(p,m,a)->{
            assertEquals("getExecutionPage",m.getName()); assertEquals(100L,a[0]);
            var query=new BeanWrapperImpl(a[1]); assertEquals(current,query.getPropertyValue("current")); assertEquals(size,query.getPropertyValue("size"));
            var value=new MaintenanceExecutionVO(); value.setId(500L); value.setPlanName("登记时计划名称"); value.setExecutedByName("原执行人");
            var page=new Page<MaintenanceExecutionVO>(current,size,23); page.setRecords(List.of(value)); return page;
        });
        return MockMvcBuilders.standaloneSetup(new MaintenancePlanController(service)).setControllerAdvice(new BusinessExceptionHandler()).build();
    }
    @Test void 路径与分页参数正确绑定并返回历史快照() throws Exception {
        mvc(2,5).perform(get("/api/maintenance-plans/100/executions").param("current","2").param("size","5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(23))
                .andExpect(jsonPath("$.data.current").value(2)).andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.records[0].planName").value("登记时计划名称"));
    }
    @Test void 省略参数使用已有分页默认值() throws Exception {
        mvc(1,10).perform(get("/api/maintenance-plans/100/executions")).andExpect(status().isOk());
    }
    @Test void 非数字路径和分页参数返回400() throws Exception {
        var mvc=mvc(1,10);
        mvc.perform(get("/api/maintenance-plans/abc/executions")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/maintenance-plans/100/executions").param("current","abc")).andExpect(status().isBadRequest());
    }
}
