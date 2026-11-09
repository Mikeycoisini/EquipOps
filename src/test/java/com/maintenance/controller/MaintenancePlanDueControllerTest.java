package com.maintenance.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.common.response.BusinessExceptionHandler;
import com.maintenance.service.MaintenancePlanServer;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 原分页入口新增dueOnly参数的HTTP契约。 */
class MaintenancePlanDueControllerTest {
    private org.springframework.test.web.servlet.MockMvc mvc(Boolean expected) {
        var service=(MaintenancePlanServer)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{MaintenancePlanServer.class},(p,m,a)->{
            assertEquals("getPlanPage",m.getName());var query=new BeanWrapperImpl(a[0]);
            assertTrue(query.isReadableProperty("dueOnly"),"分页条件应支持dueOnly");
            assertEquals(expected,query.getPropertyValue("dueOnly"));return new Page<>(1,10,0);
        });
        return MockMvcBuilders.standaloneSetup(new MaintenancePlanController(service)).setControllerAdvice(new BusinessExceptionHandler()).build();
    }
    @Test void dueOnly真假和省略均正确绑定() throws Exception {
        mvc(true).perform(get("/api/maintenance-plans").param("dueOnly","true")).andExpect(status().isOk());
        mvc(false).perform(get("/api/maintenance-plans").param("dueOnly","false")).andExpect(status().isOk());
        mvc(null).perform(get("/api/maintenance-plans")).andExpect(status().isOk());
    }
    @Test void 非布尔值返回400() throws Exception {
        mvc(null).perform(get("/api/maintenance-plans").param("dueOnly","abc")).andExpect(status().isBadRequest());
    }
}
