package com.maintenance.controller;

import com.common.response.BusinessExceptionHandler;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import com.maintenance.service.MaintenancePlanServer;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证执行登记请求号、正文和结果响应，不加载安全链或数据库。 */
class MaintenanceExecutionControllerTest {
    private int calls;
    private org.springframework.test.web.servlet.MockMvc mvc() {
        var service=(MaintenancePlanServer)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{MaintenancePlanServer.class},(p,m,a)->{
            assertEquals("createExecution",m.getName()); assertEquals(100L,a[0]); assertEquals("exec-1",a[1]);
            assertEquals("已完成巡检",new BeanWrapperImpl(a[2]).getPropertyValue("result")); calls++;
            var result=new MaintenanceExecutionVO(); result.setId(500L); result.setPlanId(100L); result.setResult("已完成巡检"); return result;
        });
        return MockMvcBuilders.standaloneSetup(new MaintenancePlanController(service)).setControllerAdvice(new BusinessExceptionHandler()).build();
    }
    @Test void 请求号路径和结果正文正确传递并返回执行记录() throws Exception {
        mvc().perform(post("/api/maintenance-plans/100/executions").header("Idempotency-Key","exec-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"已完成巡检\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(500)).andExpect(jsonPath("$.data.planId").value(100));
        assertEquals(1,calls);
    }
    @Test void 缺少请求号正文或结果不调用业务() throws Exception {
        var mvc=mvc();
        mvc.perform(post("/api/maintenance-plans/100/executions").contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"已完成巡检\"}"))
                .andExpect(status().isBadRequest());
        for(String body:new String[]{"{}","{\"result\":\"  \"}","{\"result\":\""+"a".repeat(2001)+"\"}",""}) {
            mvc.perform(post("/api/maintenance-plans/100/executions").header("Idempotency-Key","exec-1").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0,calls);
    }
}
