package com.inventory.controller;

import com.config.Exception.InventoryExportExceptionHandler;
import com.equipops.domain.enums.InventoryExportStatus;
import com.equipops.domain.vo.InventoryExportTaskVO;
import com.inventory.service.InventoryExportDownloadService;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryExportCancelControllerTest {
    MockMvc mvc;InventoryExportStatus state=InventoryExportStatus.CANCELLED;
    @BeforeEach void setup(){
        var service=(InventoryExportDownloadService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{InventoryExportDownloadService.class},(p,m,a)->{
            assertEquals("cancel",m.getName());assertEquals(9L,a[0]);var task=new InventoryExportTaskVO();task.setId(9L);task.setStatus(state);return task;
        });
        mvc=MockMvcBuilders.standaloneSetup(new InventoryExportDownloadController(service)).setControllerAdvice(new InventoryExportExceptionHandler()).build();
    }
    @Test void POST排队取消返回200及公开任务()throws Exception{mvc.perform(post("/api/inventory/exports/9/cancel")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED")).andExpect(jsonPath("$.data.runToken").doesNotExist());}
    @Test void POST运行取消返回202避免声称已经结束()throws Exception{state=InventoryExportStatus.CANCEL_REQUESTED;mvc.perform(post("/api/inventory/exports/9/cancel")).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("CANCEL_REQUESTED"));}
    @Test void GET不能改变任务状态()throws Exception{mvc.perform(get("/api/inventory/exports/9/cancel")).andExpect(status().isMethodNotAllowed());}
    @Test void 非数字路径返回400()throws Exception{mvc.perform(post("/api/inventory/exports/abc/cancel")).andExpect(status().isBadRequest());}
}
