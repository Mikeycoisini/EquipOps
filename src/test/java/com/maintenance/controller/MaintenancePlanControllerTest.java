package com.maintenance.controller;

import com.common.response.BusinessExceptionHandler;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.equipops.domain.dto.maintenance.MaintenancePlanCreateDto;
import com.maintenance.service.MaintenancePlanServer;
import java.lang.reflect.Proxy;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立MVC契约测试；不加载登录安全链、不连接数据库。 */
class MaintenancePlanControllerTest {
    private final String body = """
        {"name":"每周巡检","equipmentId":20,"planType":"INSPECTION","content":"检查异响",
         "cycleDays":7,"nextDueDate":"2099-01-01","assigneeUserId":30}
        """;

    @Test void 业务拒绝应返回统一响应和原HTTP状态() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller(request -> {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员");
        })).setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(post("/api/maintenance-plans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("需要管理员"));
    }

    @Test void 缺少必填字段应返回统一400而不进入业务() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller(request -> {
            throw new AssertionError("无效输入不能调用业务层");
        })).setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(post("/api/maintenance-plans").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test void 非整数周期或设备ID不能被静默截断() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller(request -> {
            throw new AssertionError("小数不能进入业务层");
        })).build();
        for (String input : new String[]{body.replace("\"cycleDays\":7", "\"cycleDays\":7.9"),
                body.replace("\"equipmentId\":20", "\"equipmentId\":20.5")}) {
            mvc.perform(post("/api/maintenance-plans").contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isBadRequest());
        }
    }
    /** 创建用例只替代创建方法；意外调用其他业务时立即失败。 */
    private MaintenancePlanController controller(Function<MaintenancePlanCreateDto, MaintenancePlanVO> create) {
        var service = (MaintenancePlanServer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MaintenancePlanServer.class}, (p, method, args) -> {
                    if (!method.getName().equals("createPlan")) throw new AssertionError(method.getName());
                    return create.apply((MaintenancePlanCreateDto) args[0]);
                });
        return new MaintenancePlanController(service);
    }

}
