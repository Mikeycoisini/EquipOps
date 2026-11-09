package com.inventory.controller;

import com.equipops.domain.vo.InventoryTransactionVO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 只验证独立 MVC 的入库绑定和错误格式，不加载 Session 安全链，不连接数据库。 */
class StockInControllerTest {
    private final String body = "{\"warehouseId\":10,\"sparePartId\":20,\"quantity\":2.5}";
    @Test void 请求头和JSON正确交给Service且返回流水() throws Exception {
        var controller = new StockInController((key, request) -> {
            assertEquals("request-1", key);
            assertEquals(10L, request.getWarehouseId());
            assertEquals(20L, request.getSparePartId());
            assertEquals(0, request.getQuantity().compareTo(new java.math.BigDecimal("2.5")));
            var value = new InventoryTransactionVO(); value.setId(100L); value.setRequestId(key); return value;
        });
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new StockInExceptionHandler()).build();
        mvc.perform(post("/api/inventory/stock-ins").header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.requestId").value("request-1"));
    }
    @Test void 缺请求头或非法JSON及数量返回可读400() throws Exception {
        var controller = new StockInController((key, request) -> { throw new AssertionError("非法请求不能进入业务"); });
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new StockInExceptionHandler()).build();
        mvc.perform(post("/api/inventory/stock-ins").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("业务请求号不能为空"));
        mvc.perform(post("/api/inventory/stock-ins").header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{bad json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("入库参数格式有误"));
        mvc.perform(post("/api/inventory/stock-ins").header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("2.5", "0")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }
    @Test void 数据库锁冲突不返回底层SQL或堆栈() throws Exception {
        var controller = new StockInController((key, request) -> { throw new CannotAcquireLockException("private database details"); });
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new StockInExceptionHandler()).build();
        mvc.perform(post("/api/inventory/stock-ins").header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("库存正在变更，请使用原请求号重试"));
    }
}
