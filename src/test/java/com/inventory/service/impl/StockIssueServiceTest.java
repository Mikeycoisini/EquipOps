package com.inventory.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.inventory.StockIssueCreateDto;
import com.equipops.domain.entity.*;
import com.equipops.domain.enums.*;
import com.equipops.domain.vo.StockInWarehouseCheckVO;
import com.inventory.mapper.*;
import com.workorder.mapper.WorkOrderMapper;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 在数据库边界使用替身检查真实领料编排；不验证真实数据库并发或事务回滚。 */
class StockIssueServiceTest {
    private final List<String> writes = new ArrayList<>();
    private InventoryTransactionEntity existing, concurrent, saved;
    private boolean duplicate, missingSaved, missingId, failParts;
    private int insertRows = 1, stockRows = 1, partRows = 1;
    private int currentReads, resourceReads;
    private WorkOrderStatus status = WorkOrderStatus.IN_PROGRESS;
    private Long assignee = 1L;

    private StockIssueCreateDto request() {
        return new StockIssueCreateDto(30L, 10L, 20L, new BigDecimal("2.500"), null);
    }

    private InventoryTransactionEntity completed() {
        var log = new InventoryTransactionEntity();
        log.setId(100L); log.setTenantId(7L); log.setWarehouseId(10L);
        log.setSparePartId(20L); log.setWorkOrderId(30L); log.setCreatedBy(1L);
        log.setRequestId("issue-1"); log.setTransactionNo("OUT-existing");
        log.setTransactionType(InventoryTransactionType.OUTBOUND);
        log.setReferenceType(InventoryReferenceType.WORK_ORDER);
        log.setQuantity(new BigDecimal("2.500"));
        log.setCreatedAt(LocalDateTime.of(2026, 10, 28, 10, 0));
        return log;
    }

    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, handler));
    }

    private StockIssueServiceImpl service() {
        var service = new StockIssueServiceImpl();
        var access = proxy(CurrentAccessService.class, (p,m,a) -> switch(m.getName()) {
            case "requireActiveTenantId" -> 7L;
            case "requireEnabledUserId" -> 1L;
            default -> throw new AssertionError(m.getName());
        });
        var inventory = proxy(InventoryTransactionMapper.class, (p,m,a) -> {
            if (m.getName().equals("selectList")) return existing == null ? List.of() : List.of(existing);
            throw new AssertionError(m.getName());
        });
        var orders = proxy(WorkOrderMapper.class, (p,m,a) -> {
            assertEquals("selectTenantAnduserId", m.getName());
            assertArrayEquals(new Object[]{7L,30L},a);
            var order = new WorkOrderEntity();
            order.setId(30L); order.setTenantId(7L);
            order.setStatus(status); order.setAssigneeUserId(assignee);
            return order;
        });
        var mapper = proxy(StockIssueMapper.class, (p,m,a) -> {
            switch (m.getName()) {
                case "lockWarehouseForIssue":
                    resourceReads++;
                    assertArrayEquals(new Object[]{7L,10L},a);
                    return new StockInWarehouseCheckVO(10L,true,40L,true);
                case "lockSparePartEnabled":
                    assertArrayEquals(new Object[]{7L,20L},a);
                    return true;
                case "insertStockIssue":
                    writes.add("log");
                    if (duplicate) throw new DuplicateKeyException("test collision");
                    var log = (InventoryTransactionEntity)a[0];
                    if (!missingId) log.setId(100L);
                    saved = new InventoryTransactionEntity();
                    BeanUtils.copyProperties(log,saved);
                    saved.setCreatedAt(LocalDateTime.of(2026,10,28,10,0));
                    return insertRows;
                case "findByRequestForUpdate":
                    currentReads++;
                    assertArrayEquals(new Object[]{7L,"issue-1"},a);
                    return concurrent;
                case "decreaseStock":
                    assertArrayEquals(new Object[]{7L,10L,20L,new BigDecimal("2.500")},a);
                    writes.add("stock");
                    return stockRows;
                case "ensureWorkOrderPartRow":
                    assertArrayEquals(new Object[]{7L,30L,20L},a);
                    writes.add("ensurePart");
                    return 0; // 已有用料行时不应覆盖计划量、领用量和退回量。
                case "increaseIssuedQuantity":
                    assertArrayEquals(new Object[]{7L,30L,20L,new BigDecimal("2.500")},a);
                    writes.add("issued");
                    if (failParts) throw new IllegalStateException("test database failure");
                    return partRows;
                case "findById":
                    assertArrayEquals(new Object[]{7L,100L},a);
                    return missingSaved ? null : saved;
                default: throw new AssertionError(m.getName());
            }
        });
        ReflectionTestUtils.setField(service,"currentAccessService",access);
        ReflectionTestUtils.setField(service,"inventoryTransactionMapper",inventory);
        ReflectionTestUtils.setField(service,"workOrderMapper",orders);
        ReflectionTestUtils.setField(service,"stockIssueMapper",mapper);
        return service;
    }

    private void rejects(int expected) {
        var error = assertThrows(ResponseStatusException.class, () -> service().issue("issue-1",request()));
        assertEquals(expected,error.getStatusCode().value());
    }

    @Test void 首次领料依次写流水扣库存累计用料并返回数据库结果() {
        var result = service().issue("issue-1",request());
        assertNotNull(result);
        assertEquals(100L,result.getId()); assertEquals(7L,result.getTenantId());
        assertEquals(1L,result.getCreatedBy()); assertEquals(30L,result.getWorkOrderId());
        assertEquals(InventoryTransactionType.OUTBOUND,result.getTransactionType());
        assertEquals(InventoryReferenceType.WORK_ORDER,result.getReferenceType());
        assertEquals(new BigDecimal("2.500"),result.getQuantity());
        assertEquals("issue-1",result.getRequestId());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getTransactionNo());
        assertTrue(result.getTransactionNo().length() <= 40);
        assertEquals(List.of("log","stock","ensurePart","issued"),writes);
    }
    @Test void 相同请求可重放且不重复扣库存() {
        existing = completed(); status = WorkOrderStatus.COMPLETED;
        var request = request(); request.setQuantity(new BigDecimal("2.5"));
        var result = service().issue("issue-1",request);
        assertEquals(100L,result.getId()); assertEquals("OUT-existing",result.getTransactionNo());
        assertTrue(writes.isEmpty()); assertEquals(0,resourceReads);
    }
    @Test void 不同操作人不能复用原请求号() {
        existing=completed(); existing.setCreatedBy(2L); rejects(409); assertTrue(writes.isEmpty());
    }
    @Test void 其他库存命令不能冒充领料成功() {
        existing=completed(); existing.setTransactionType(InventoryTransactionType.INBOUND);
        existing.setReferenceType(InventoryReferenceType.MANUAL); existing.setWorkOrderId(null);
        rejects(409); assertTrue(writes.isEmpty());
    }
    @Test void 并发重复插入用当前读重放且不修改余额用料() {
        duplicate=true; concurrent=completed();
        assertEquals("OUT-existing",service().issue("issue-1",request()).getTransactionNo());
        assertEquals(1,currentReads); assertEquals(List.of("log"),writes);
    }
    @Test void 并发请求不同内容拒绝重放() {
        duplicate=true; concurrent=completed(); concurrent.setNote("changed");
        rejects(409); assertEquals(List.of("log"),writes);
    }
    @Test void 并发请求其他操作人或来源不能重放() {
        duplicate=true; concurrent=completed(); concurrent.setCreatedBy(2L); rejects(409);
        concurrent=completed(); concurrent.setReferenceType(InventoryReferenceType.MANUAL); rejects(409);
        assertFalse(writes.contains("stock"));
    }
    @Test void 非请求号的唯一键冲突不能当作成功() {
        duplicate=true; rejects(409); assertEquals(List.of("log"),writes);
    }
    @Test void 库存不足或没有余额行返回冲突且不累计用料() {
        stockRows=0; rejects(409); assertEquals(List.of("log","stock"),writes);
    }
    @Test void 用料累计超过上限必须抛异常交给事务回滚() {
        partRows=0; rejects(409);
        assertEquals(List.of("log","stock","ensurePart","issued"),writes);
    }
    @Test void 用料写库异常必须继续抛出不能伪装成功() {
        failParts=true;
        assertThrows(IllegalStateException.class, () -> service().issue("issue-1",request()));
    }
    @Test void 插入失败或未回填主键不允许扣库存() {
        insertRows=0; rejects(500);
        insertRows=1; missingId=true; rejects(500);
        assertFalse(writes.contains("stock"));
    }
    @Test void 保存后查不到流水不能返回成功() {
        missingSaved=true; rejects(500);
    }
    @Test void 工单非维修中或者不是本人负责时不允许写入() {
        status=WorkOrderStatus.COMPLETED; rejects(409);
        status=WorkOrderStatus.IN_PROGRESS; assignee=null; rejects(403);
        assertTrue(writes.isEmpty());
    }
    @Test void 空标识和空数量应返回参数错误而不是空指针() {
        var r=request(); r.setWarehouseId(null);
        var error=assertThrows(ResponseStatusException.class, () -> service().issue("issue-1",r));
        assertEquals(400,error.getStatusCode().value());
        r.setWarehouseId(10L); r.setQuantity(null);
        error=assertThrows(ResponseStatusException.class, () -> service().issue("issue-1",r));
        assertEquals(400,error.getStatusCode().value()); assertTrue(writes.isEmpty());
    }
    @Test void 非法业务请求号必须在写库前拒绝() {
        for (String key : List.of("with space", "a".repeat(65), "中文")) {
            var error=assertThrows(ResponseStatusException.class, () -> service().issue(key,request()));
            assertEquals(400,error.getStatusCode().value());
        }
        assertTrue(writes.isEmpty());
    }
}
