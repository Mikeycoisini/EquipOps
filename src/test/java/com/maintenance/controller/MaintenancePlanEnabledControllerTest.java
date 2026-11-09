package com.maintenance.controller;

import com.common.response.BusinessExceptionHandler;
import com.maintenance.service.MaintenancePlanServer;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 启停接口的路径、布尔值和必填契约，不连接数据库。 */
class MaintenancePlanEnabledControllerTest {
    private int calls;
    private org.springframework.test.web.servlet.MockMvc mvc(boolean expected) {
        var service = (MaintenancePlanServer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MaintenancePlanServer.class}, (p, m, args) -> {
                    assertEquals("updatePlanEnabled", m.getName()); assertEquals(100L, args[0]);
                    assertEquals(expected, new BeanWrapperImpl(args[1]).getPropertyValue("enabled"));
                    calls++; return null;
                });
        return MockMvcBuilders.standaloneSetup(new MaintenancePlanController(service))
                .setControllerAdvice(new BusinessExceptionHandler()).build();
    }
    @Test void true和false都能正确传入业务并返回成功() throws Exception {
        for (boolean value : new boolean[]{true, false}) {
            mvc(value).perform(patch("/api/maintenance-plans/100/enabled")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + value + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        }
        assertEquals(2, calls);
    }
    @Test void 缺少或空启用状态不能进入业务() throws Exception {
        for (String body : new String[]{"{}", "{\"enabled\":null}"}) {
            mvc(false).perform(patch("/api/maintenance-plans/100/enabled")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        assertEquals(0, calls);
    }
}
