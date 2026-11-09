package com.maintenance.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.common.response.BusinessExceptionHandler;
import com.equipops.domain.enums.MaintenancePlanType;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.maintenance.service.MaintenancePlanServer;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 只验证分页HTTP契约；不加载安全链，也不连接数据库。 */
class MaintenancePlanPageControllerTest {
    private MockMvc mvc(Function<BeanWrapper, Page<MaintenancePlanVO>> page) {
        var service = (MaintenancePlanServer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MaintenancePlanServer.class}, (p, method, args) -> {
                    assertEquals("getPlanPage", method.getName());
                    return page.apply(new BeanWrapperImpl(args[0]));
                });
        return MockMvcBuilders.standaloneSetup(new MaintenancePlanController(service))
                .setControllerAdvice(new BusinessExceptionHandler()).build();
    }

    @Test void GET绑定所有筛选字段并返回真实分页结构() throws Exception {
        var mvc = mvc(query -> {
            assertEquals(2L, query.getPropertyValue("current"));
            assertEquals(5L, query.getPropertyValue("size"));
            assertEquals("巡检", query.getPropertyValue("keyword"));
            assertEquals(20L, query.getPropertyValue("equipmentId"));
            assertEquals(MaintenancePlanType.INSPECTION, query.getPropertyValue("planType"));
            assertEquals(false, query.getPropertyValue("enabled"));
            var plan = new MaintenancePlanVO(); plan.setId(100L); plan.setName("每周巡检");
            plan.setEquipmentName("空压机"); plan.setAssigneeName("维修员");
            var result = new Page<MaintenancePlanVO>(2, 5, 23); result.setRecords(List.of(plan));
            return result;
        });
        mvc.perform(get("/api/maintenance-plans").param("current", "2").param("size", "5")
                .param("keyword", "巡检").param("equipmentId", "20")
                .param("planType", "INSPECTION").param("enabled", "false").param("tenantId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(23)).andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.size").value(5)).andExpect(jsonPath("$.data.records[0].id").value(100))
                .andExpect(jsonPath("$.data.records[0].equipmentName").value("空压机"))
                .andExpect(jsonPath("$.data.records[0].assigneeName").value("维修员"));
    }

    @Test void 默认参数与无记录时返回空分页() throws Exception {
        mvc(query -> {
            assertEquals(1L, query.getPropertyValue("current"));
            assertEquals(10L, query.getPropertyValue("size"));
            assertNull(query.getPropertyValue("enabled"));
            assertNull(query.getPropertyValue("equipmentId"));
            return new Page<>(1, 10, 0);
        }).perform(get("/api/maintenance-plans"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    @Test void 无效设备类型启用状态和超长关键字返回400() throws Exception {
        var mvc = mvc(query -> { throw new AssertionError("非法条件不能调用业务层"); });
        for (var input : List.of(new String[]{"equipmentId", "0"}, new String[]{"equipmentId", "20.5"},
                new String[]{"planType", "UNKNOWN"}, new String[]{"enabled", "abc"},
                new String[]{"keyword", "a".repeat(161)}, new String[]{"current", "abc"})) {
            mvc.perform(get("/api/maintenance-plans").param(input[0], input[1]))
                    .andExpect(status().isBadRequest());
        }
    }
}
