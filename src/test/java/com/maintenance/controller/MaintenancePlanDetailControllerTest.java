package com.maintenance.controller;

import com.common.response.BusinessExceptionHandler;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.maintenance.service.MaintenancePlanServer;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 详情路由、路径参数与统一响应契约，不加载安全链或数据库。 */
class MaintenancePlanDetailControllerTest {
    private MockMvc mvc(boolean missing) {
        var service = (MaintenancePlanServer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MaintenancePlanServer.class}, (p, method, args) -> {
                    assertEquals("getPlanDetail", method.getName());
                    assertArrayEquals(new Object[]{100L}, args);
                    if (missing) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "维护计划不存在");
                    var result = new MaintenancePlanVO(); result.setId(100L); result.setName("每周巡检");
                    result.setEquipmentName("空压机"); result.setAssigneeName("维修员"); result.setEnabled(false);
                    return result;
                });
        return MockMvcBuilders.standaloneSetup(new MaintenancePlanController(service))
                .setControllerAdvice(new BusinessExceptionHandler()).build();
    }
    @Test void 从路径读取计划ID并返回包含名称的详情() throws Exception {
        mvc(false).perform(get("/api/maintenance-plans/100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.equipmentName").value("空压机"))
                .andExpect(jsonPath("$.data.assigneeName").value("维修员"))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }
    @Test void 不存在返回统一404() throws Exception {
        mvc(true).perform(get("/api/maintenance-plans/100"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("维护计划不存在"));
    }
    @Test void 非数字路径ID返回400() throws Exception {
        mvc(false).perform(get("/api/maintenance-plans/abc")).andExpect(status().isBadRequest());
    }
}
