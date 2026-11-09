package com.inventory.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.vo.WarehouseStockVO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 MVC 测试参数绑定与 Result 分页格式，不加载 Session 安全链或数据库。 */
class WarehouseStockControllerTest {
    @Test void URL条件正确绑定且保留分页响应() throws Exception {
        var controller = new WarehouseStockController(queryService(query -> {
            assertEquals(2, query.getCurrent());
            assertEquals(5, query.getSize());
            assertEquals(10L, query.getWarehouseId());
            assertEquals(20L, query.getSparePartId());
            assertEquals("轴承", query.getKeyword());
            assertTrue(query.getLowStockOnly());
            var page = new Page<WarehouseStockVO>(2, 5, 23);
            page.setRecords(List.of(new WarehouseStockVO()));
            return page;
        }));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WarehouseStockExceptionHandler()).build();
        mvc.perform(get("/api/warehouse-stocks").param("current", "2").param("size", "5")
                        .param("warehouseId", "10").param("sparePartId", "20").param("keyword", "轴承")
                        .param("lowStockOnly", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(23))
                .andExpect(jsonPath("$.data.current").value(2)).andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.records.length()").value(1));
    }

    @Test void 非法ID关键字和类型绑定都返回可读400() throws Exception {
        var controller = new WarehouseStockController(queryService(query -> { throw new AssertionError("非法参数不能进入业务查询"); }));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WarehouseStockExceptionHandler()).build();
        mvc.perform(get("/api/warehouse-stocks").param("warehouseId", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/warehouse-stocks").param("keyword", "a".repeat(161)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/warehouse-stocks").param("warehouseId", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("查询参数类型有误"));
        mvc.perform(get("/api/warehouse-stocks").param("lowStockOnly", "maybe"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("查询参数类型有误"));
    }

    @Test void 业务异常沿用既有Result格式() throws Exception {
        var controller = new WarehouseStockController(queryService(query -> { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在"); }));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WarehouseStockExceptionHandler()).build();
        mvc.perform(get("/api/warehouse-stocks").param("warehouseId", "10"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("仓库不存在"));
    }

    private com.inventory.service.WarehouseStockService queryService(
            java.util.function.Function<com.equipops.domain.dto.inventory.WarehouseStockPageQueryDto,
                    com.baomidou.mybatisplus.core.metadata.IPage<WarehouseStockVO>> query) {
        return new com.inventory.service.WarehouseStockService() {
            @Override
            public com.baomidou.mybatisplus.core.metadata.IPage<WarehouseStockVO> getStockPage(
                    com.equipops.domain.dto.inventory.WarehouseStockPageQueryDto request) {
                return query.apply(request);
            }
            @Override
            public WarehouseStockVO updateMinStockQuantity(Long id,
                    com.equipops.domain.dto.inventory.WarehouseStockMinQuantityUpdateDto request) {
                throw new AssertionError("余额查询测试不应调用阈值更新");
            }
        };
    }
}
