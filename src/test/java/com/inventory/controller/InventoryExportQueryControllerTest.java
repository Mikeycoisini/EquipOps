package com.inventory.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.config.Exception.InventoryExportExceptionHandler;
import com.equipops.domain.dto.inventory.InventoryExportPageQueryDto;
import com.equipops.domain.enums.InventoryExportStatus;
import com.equipops.domain.vo.InventoryExportTaskVO;
import com.inventory.service.InventoryExportService;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryExportQueryControllerTest {
    MockMvc mvc;
    @BeforeEach void setup(){
        var service=(InventoryExportService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{InventoryExportService.class},(p,m,a)->{
            var task=new InventoryExportTaskVO();task.setId(9L);task.setStatus(InventoryExportStatus.SUCCEEDED);task.setProcessedRows(45L);
            if(m.getName().equals("getTaskDetail")){assertEquals(9L,a[0]);return task;}
            assertEquals("getTaskPage",m.getName());var query=(InventoryExportPageQueryDto)a[0];assertEquals(InventoryExportStatus.SUCCEEDED,query.getStatus());
            var page=new Page<InventoryExportTaskVO>(query.getCurrent(),query.getSize());page.setTotal(1);page.setRecords(List.of(task));return page;
        });
        mvc=MockMvcBuilders.standaloneSetup(new InventoryExportController(service)).setControllerAdvice(new InventoryExportExceptionHandler()).build();
    }
    @Test void GET分页正确绑定筛选并返回公开分页对象()throws Exception{mvc.perform(get("/api/inventory/exports").param("current","2").param("size","5").param("status","SUCCEEDED")).andExpect(status().isOk()).andExpect(jsonPath("$.data.current").value(2)).andExpect(jsonPath("$.data.size").value(5)).andExpect(jsonPath("$.data.records[0].id").value(9)).andExpect(jsonPath("$.data.records[0].storageKey").doesNotExist()).andExpect(jsonPath("$.data.records[0].runToken").doesNotExist());}
    @Test void GET详情返回任务公开信息()throws Exception{mvc.perform(get("/api/inventory/exports/9")).andExpect(status().isOk()).andExpect(jsonPath("$.data.processedRows").value(45)).andExpect(jsonPath("$.data.ownerInstanceId").doesNotExist()).andExpect(jsonPath("$.data.leaseUntil").doesNotExist()).andExpect(jsonPath("$.data.deadlineAt").doesNotExist());}
    @Test void 非法状态或分页参数按统一结构返回400()throws Exception{for(String query:List.of("status=INVALID","current=abc","size=abc"))mvc.perform(get("/api/inventory/exports?"+query)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));}
    @Test void 非数字路径ID按统一结构返回400()throws Exception{mvc.perform(get("/api/inventory/exports/not-a-number")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));}
}
