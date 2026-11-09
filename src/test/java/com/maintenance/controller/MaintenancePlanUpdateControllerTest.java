package com.maintenance.controller;

import com.common.response.BusinessExceptionHandler;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.maintenance.service.MaintenancePlanServer;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证完整修改的HTTP契约，不加载安全链或数据库。 */
class MaintenancePlanUpdateControllerTest {
    private final String body = """
        {"name":"新计划","equipmentId":20,"planType":"INSPECTION","content":"检查异响",
         "cycleDays":7,"nextDueDate":"2099-01-01","assigneeUserId":30}
        """;
    private int calls;
    private org.springframework.test.web.servlet.MockMvc mvc() {
        var service = (MaintenancePlanServer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MaintenancePlanServer.class}, (p, m, args) -> {
                    assertEquals("updatePlan", m.getName()); assertEquals(100L, args[0]);
                    var request = new BeanWrapperImpl(args[1]);
                    assertEquals("新计划", request.getPropertyValue("name"));
                    assertEquals(20L, request.getPropertyValue("equipmentId"));
                    calls++; var plan = new MaintenancePlanVO(); plan.setId(100L); plan.setName("新计划");
                    return plan;
                });
        return MockMvcBuilders.standaloneSetup(new MaintenancePlanController(service))
                .setControllerAdvice(new BusinessExceptionHandler()).build();
    }
    @Test void PUT路径和完整请求返回更新详情() throws Exception {
        mvc().perform(put("/api/maintenance-plans/100").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.name").value("新计划"));
        assertEquals(1, calls);
    }
    @Test void 必填约束与整数限制在进入业务前生效() throws Exception {
        var mvc = mvc();
        for (String input : new String[]{"{}", body.replace("\"cycleDays\":7", "\"cycleDays\":7.5"),
                body.replace("\"equipmentId\":20", "\"equipmentId\":20.5"),
                body.replace("\"cycleDays\":7", "\"cycleDays\":0"),
                body.replace("\"planType\":\"INSPECTION\"", "\"planType\":\"UNKNOWN\"")}) {
            mvc.perform(put("/api/maintenance-plans/100").contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, calls);
    }
}
