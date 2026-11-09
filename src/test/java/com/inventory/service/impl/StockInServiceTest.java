package com.inventory.service.impl;

import com.equipops.domain.dto.inventory.StockInCreateDto;
import com.equipops.domain.enums.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 对完整入库方法的权限、重试、失败和数值边界进行独立测试，不连接数据库。 */
class StockInServiceTest {
    private final StockInTestFixture f = new StockInTestFixture();
    private void rejects(int status, String key, StockInCreateDto request) {
        var error = assertThrows(ResponseStatusException.class, () -> f.service().stocktoIn(key, request));
        assertEquals(status, error.getStatusCode().value());
    }

    @Test void 首次入库增加一次余额并返回可信流水() {
        var result = f.service().stocktoIn("request-1", f.request());
        assertNotNull(result);
        assertEquals(100L, result.getId());
        assertEquals(7L, result.getTenantId());
        assertEquals(1L, result.getCreatedBy());
        assertEquals(InventoryTransactionType.INBOUND, result.getTransactionType());
        assertEquals(InventoryReferenceType.MANUAL, result.getReferenceType());
        assertEquals("request-1", result.getRequestId());
        assertEquals(new BigDecimal("7.500"), f.balance);
        assertEquals(List.of("log", "ensure", "increase"), f.writes);
        assertNotNull(result.getCreatedAt());
        assertTrue(result.getTransactionNo().length() <= 40);
    }

    @Test void 成功重试返回原流水且不重复加库存即使资源后来停用() {
        f.existing = f.completed();
        f.warehouse = null;
        var result = f.service().stocktoIn("request-1", f.request());
        assertNotNull(result);
        assertEquals("IN-existing", result.getTransactionNo());
        assertTrue(f.writes.isEmpty());
        assertEquals(0, f.resourceReads);
        assertEquals(new BigDecimal("5.000"), f.balance);
    }

    @Test void 数值相等的小数表示可以重放() {
        f.existing = f.completed();
        var request = f.request();
        request.setQuantity(new BigDecimal("2.5"));
        assertNotNull(f.service().stocktoIn("request-1", request));
        assertTrue(f.writes.isEmpty());
    }

    @Test void 相同请求号内容不同返回冲突() {
        f.existing = f.completed();
        var changed = f.request();
        changed.setWarehouseId(11L);
        rejects(409, "request-1", changed);
        changed = f.request(); changed.setSparePartId(21L); rejects(409, "request-1", changed);
        changed = f.request(); changed.setQuantity(new BigDecimal("3")); rejects(409, "request-1", changed);
        changed = f.request(); changed.setReferenceText(null); rejects(409, "request-1", changed);
        changed = f.request(); changed.setNote("其他备注"); rejects(409, "request-1", changed);
        assertTrue(f.writes.isEmpty());
    }

    @Test void 不同操作人不能重放他人的入库请求() {
        f.existing = f.completed();
        f.userId = 2L;
        rejects(409, "request-1", f.request());
        assertTrue(f.writes.isEmpty());
    }

    @Test void 请求号不能复用其他库存命令的流水() {
        f.existing = f.completed();
        f.existing.setTransactionType(InventoryTransactionType.OUTBOUND);
        rejects(409, "request-1", f.request());
        assertTrue(f.writes.isEmpty());
    }

    @Test void 非管理员即使已有成功流水也拒绝操作() {
        f.roles = Set.of("ACCEPTOR");
        f.existing = f.completed();
        rejects(403, "request-1", f.request());
        assertTrue(f.writes.isEmpty());
    }

    @Test void 并发唯一键冲突后当前读重放且不写余额() {
        f.collide = true;
        f.concurrent = f.completed();
        var result = f.service().stocktoIn("request-1", f.request());
        assertNotNull(result);
        assertEquals("IN-existing", result.getTransactionNo());
        assertEquals(1, f.currentReads);
        assertEquals(List.of("log"), f.writes);
    }

    @Test void 并发相同请求号内容不同返回冲突() {
        f.collide = true;
        f.concurrent = f.completed();
        f.concurrent.setNote("不同请求");
        rejects(409, "request-1", f.request());
        assertEquals(List.of("log"), f.writes);
    }

    @Test void 其他唯一键冲突不能冒充入库成功() {
        f.collide = true;
        rejects(409, "request-1", f.request());
        assertEquals(List.of("log"), f.writes);
    }

    @Test void 无效请求号及参数不进入写库() {
        for (String key : Arrays.asList(null, "", " ", "x y", "a".repeat(65), "中文")) {
            rejects(400, key, f.request());
        }
        rejects(400, "request-1", null);
        for (BigDecimal quantity : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1"),
                new BigDecimal("0.0001"), new BigDecimal("100000000000"))) {
            var request = f.request(); request.setQuantity(quantity);
            rejects(400, "request-1", request);
        }
        assertTrue(f.writes.isEmpty());
    }

    @Test void 流水未插入或未回填主键必须失败且不加库存() {
        f.insertRows = 0;
        rejects(500, "request-1", f.request());
        f.insertRows = 1; f.noGeneratedId = true;
        rejects(500, "request-1", f.request());
        assertFalse(f.writes.contains("increase"));
    }

    @Test void 库存溢出必须抛异常交给事务回滚() {
        f.increaseRows = 0;
        rejects(409, "request-1", f.request());
        assertEquals(new BigDecimal("5.000"), f.balance);
    }

    @Test void 库存写入异常不能吞掉或返回成功() {
        f.failStock = true;
        assertThrows(IllegalStateException.class, () -> f.service().stocktoIn("request-1", f.request()));
    }

    @Test void 保存后读取不到流水不能伪装成功() {
        f.noSavedResult = true;
        rejects(500, "request-1", f.request());
    }
}
