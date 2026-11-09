package com.maintenance.controller;

import com.common.response.BusinessExceptionHandler;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import com.maintenance.service.MaintenancePlanServer;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立MVC验证详情路由与错误响应，不加载安全链或数据库。 */
class MaintenanceExecutionDetailControllerTest {
    private org.springframework.test.web.servlet.MockMvc mvc(boolean missing) throws Exception {
        var service=(MaintenancePlanServer)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{MaintenancePlanServer.class},(p,m,a)->{
            assertEquals("getExecutionDetail",m.getName());assertArrayEquals(new Object[]{500L},a);
            if(missing)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"执行记录不存在");
            var result=new MaintenanceExecutionVO();result.setId(500L);result.setPlanId(100L);result.setPlanName("历史计划名称");result.setResult("已完成巡检");return result;
        });
        Class<?> type;
        try { type=Class.forName("com.maintenance.controller.MaintenanceExecutionController"); }
        catch(ClassNotFoundException error) { fail("执行记录详情控制层尚未创建");return null; }
        Object controller=type.getConstructor(MaintenancePlanServer.class).newInstance(service);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new BusinessExceptionHandler()).build();
    }
    @Test void 执行记录ID从路径读取并返回历史详情() throws Exception {
        mvc(false).perform(get("/api/maintenance-executions/500"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(500))
                .andExpect(jsonPath("$.data.planId").value(100)).andExpect(jsonPath("$.data.planName").value("历史计划名称"));
    }
    @Test void 不存在返回统一404() throws Exception {
        mvc(true).perform(get("/api/maintenance-executions/500"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }
    @Test void 非数字执行记录ID返回400() throws Exception {
        mvc(false).perform(get("/api/maintenance-executions/abc")).andExpect(status().isBadRequest());
    }
}
