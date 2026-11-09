package com.inventory.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.inventory.WarehouseStockPageQueryDto;
import com.equipops.domain.vo.WarehouseStockVO;
import com.inventory.mapper.WarehouseStockMapper;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 验证实际 Service 的企业隔离、参数处理与分页结果；数据库边界使用内存代理。 */
class WarehouseStockServiceTest {
    private long warehouseCount = 1;
    private long partCount = 1;
    private boolean denyAccess;
    private int pageCalls;
    private long requestedCurrent;
    private long requestedSize;
    private String keyword;
    private final WarehouseStockVO balance = new WarehouseStockVO();

    private WarehouseStockServiceImpl service() {
        CurrentAccessService access = (CurrentAccessService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CurrentAccessService.class}, (p, m, args) -> {
                    assertEquals("requireActiveTenantId", m.getName());
                    if (denyAccess) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "成员已失效");
                    return 7L;
                });
        WarehouseStockMapper mapper = (WarehouseStockMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WarehouseStockMapper.class}, (p, m, args) -> {
                    switch (m.getName()) {
                        case "countTenantWarehouse":
                            assertArrayEquals(new Object[]{7L, 10L}, args);
                            return warehouseCount;
                        case "countTenantSparePart":
                            assertArrayEquals(new Object[]{7L, 20L}, args);
                            return partCount;
                        case "selectStockPage":
                            pageCalls++;
                            assertEquals(7L, args[1]);
                            Page<?> page = (Page<?>) args[0];
                            requestedCurrent = page.getCurrent();
                            requestedSize = page.getSize();
                            keyword = (String) args[3];
                            Page<WarehouseStockVO> result = new Page<>(page.getCurrent(), page.getSize(), 23);
                            result.setRecords(List.of(balance));
                            return result;
                        default: throw new AssertionError("查询不应调用写入或其他方法：" + m.getName());
                    }
                });
        return new WarehouseStockServiceImpl(access, mapper);
    }

    private void rejects(int status, WarehouseStockPageQueryDto query) {
        var error = assertThrows(ResponseStatusException.class, () -> service().getStockPage(query));
        assertEquals(status, error.getStatusCode().value());
        assertEquals(0, pageCalls);
    }

    @Test void 查询返回真实余额精度及数据库分页总数() {
        balance.setQuantity(new BigDecimal("1.125"));
        balance.setMinStockQuantity(new BigDecimal("2.000"));
        balance.setLowStock(true);
        var query = new WarehouseStockPageQueryDto();
        query.setWarehouseId(10L);
        query.setSparePartId(20L);
        query.setCurrent(2);
        query.setSize(5);
        query.setKeyword("  轴承  ");
        IPage<WarehouseStockVO> result = service().getStockPage(query);
        assertEquals(23, result.getTotal());
        assertEquals(2, result.getCurrent());
        assertEquals(5, result.getSize());
        assertEquals(new BigDecimal("1.125"), result.getRecords().get(0).getQuantity());
        assertTrue(result.getRecords().get(0).getLowStock());
        assertEquals("轴承", keyword);
    }

    @Test void 分页上限和负页码按现有约定处理() {
        var query = new WarehouseStockPageQueryDto();
        query.setCurrent(-2);
        query.setSize(1000);
        service().getStockPage(query);
        assertEquals(1, requestedCurrent);
        assertEquals(100, requestedSize);
    }

    @Test void 零条分页大小按一条处理且空白关键字不筛选() {
        var query = new WarehouseStockPageQueryDto();
        query.setSize(0);
        query.setKeyword(" \t ");
        service().getStockPage(query);
        assertEquals(1, requestedSize);
        assertNull(keyword);
    }

    @Test void 默认查询不要求提供仓库或备件ID() {
        var result = service().getStockPage(new WarehouseStockPageQueryDto());
        assertEquals(1, result.getCurrent());
        assertEquals(10, result.getSize());
    }

    @Test void 未知或其他企业仓库拒绝查询() {
        warehouseCount = 0;
        var query = new WarehouseStockPageQueryDto();
        query.setWarehouseId(10L);
        rejects(404, query);
    }

    @Test void 未知或其他企业备件拒绝查询() {
        partCount = 0;
        var query = new WarehouseStockPageQueryDto();
        query.setSparePartId(20L);
        rejects(404, query);
    }

    @Test void 非法ID或超长关键字拒绝数据库分页() {
        for (Long id : List.of(0L, -1L)) {
            var warehouse = new WarehouseStockPageQueryDto();
            warehouse.setWarehouseId(id);
            rejects(400, warehouse);
            var part = new WarehouseStockPageQueryDto();
            part.setSparePartId(id);
            rejects(400, part);
        }
        var longKeyword = new WarehouseStockPageQueryDto();
        longKeyword.setKeyword("a".repeat(161));
        rejects(400, longKeyword);
        rejects(400, null);
    }

    @Test void 当前成员失效时不查询库存() {
        denyAccess = true;
        rejects(403, new WarehouseStockPageQueryDto());
    }
}
