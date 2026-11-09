package com.inventory.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.inventory.StockReturnCreateDto;
import com.equipops.domain.entity.*;
import com.equipops.domain.enums.*;
import com.equipops.domain.vo.StockInWarehouseCheckVO;
import com.inventory.mapper.StockReturnMapper;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 数据库边界替身验证真实业务编排，不模拟真实 MySQL 并发或 Spring 事务回滚。 */
class StockReturnServiceTest {
    private final List<String> writes=new ArrayList<>();
    private InventoryTransactionEntity existing, concurrent, saved;
    private InventoryTransactionEntity original=source();
    private InventoryTransactionEntity locked;
    private StockInWarehouseCheckVO warehouse=new StockInWarehouseCheckVO(10L,true,40L,true);
    private Boolean partEnabled=true;
    private WorkOrderStatus status=WorkOrderStatus.IN_PROGRESS;
    private Long assignee=1L;
    private boolean duplicate, missingId, missingSaved, failStock;
    private int insertRows=1, stockRows=1, partRows=1, currentReads, resourceReads;
    private List<BigDecimal> returned=List.of(new BigDecimal("2.000"));

    private StockReturnCreateDto request() { return new StockReturnCreateDto(100L,new BigDecimal("2.000"),null); }
    private InventoryTransactionEntity source() {
        var log=new InventoryTransactionEntity();
        log.setId(100L);log.setTenantId(7L);log.setWarehouseId(10L);log.setSparePartId(20L);
        log.setWorkOrderId(30L);log.setQuantity(new BigDecimal("5.000"));log.setCreatedBy(1L);
        log.setTransactionType(InventoryTransactionType.OUTBOUND);log.setReferenceType(InventoryReferenceType.WORK_ORDER);
        return log;
    }
    private InventoryTransactionEntity completed() {
        var log=source();log.setId(200L);log.setQuantity(new BigDecimal("2.000"));
        log.setTransactionType(InventoryTransactionType.INBOUND);log.setRequestId("return-1");
        log.setTransactionNo("RET-existing");log.setCreatedAt(LocalDateTime.of(2026,10,28,20,0));
        ReflectionTestUtils.setField(log,"issueTransactionId",100L);
        return log;
    }
    private <T> T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type},handler));
    }
    private StockReturnServiceImpl service() {
        var service=new StockReturnServiceImpl();
        var access=proxy(CurrentAccessService.class,(p,m,a)->switch(m.getName()) {
            case "requireActiveTenantId" -> 7L;
            case "requireEnabledUserId" -> 1L;
            default -> throw new AssertionError(m.getName());
        });
        var mapper=proxy(StockReturnMapper.class,(p,m,a)-> {
            switch(m.getName()) {
                case "findByRequest":
                    assertArrayEquals(new Object[]{7L,"return-1"},a); return existing;
                case "findByRequestForUpdate":
                    assertArrayEquals(new Object[]{7L,"return-1"},a); currentReads++; return concurrent;
                case "selectNowTenant":
                    assertArrayEquals(new Object[]{7L,100L},a); return original;
                case "lockWarehouseForReturn":
                    assertArrayEquals(new Object[]{7L,10L},a);resourceReads++;return warehouse;
                case "lockSparePartEnabled":
                    assertArrayEquals(new Object[]{7L,20L},a);return partEnabled;
                case "lockWorkOrderForReturn":
                    assertArrayEquals(new Object[]{7L,30L},a);
                    var order=new WorkOrderEntity();order.setId(30L);order.setTenantId(7L);
                    order.setStatus(status);order.setAssigneeUserId(assignee);return order;
                case "lockIssueForReturn":
                    assertArrayEquals(new Object[]{7L,100L},a);return locked==null?original:locked;
                case "insertStockReturn":
                    writes.add("log");if(duplicate)throw new DuplicateKeyException("test unique key");
                    var log=(InventoryTransactionEntity)a[0];if(!missingId)log.setId(200L);
                    saved=new InventoryTransactionEntity();BeanUtils.copyProperties(log,saved);
                    // 数据库替身模拟INSERT SELECT的来源字段和数据库UUID；这些值不再由Java组装。
                    assertEquals(7L,log.getTenantId());
                    assertEquals(100L,ReflectionTestUtils.getField(log,"issueTransactionId"));
                    saved.setWarehouseId(original.getWarehouseId());saved.setSparePartId(original.getSparePartId());
                    saved.setWorkOrderId(original.getWorkOrderId());saved.setTransactionNo("RET-db-generated");
                    saved.setTransactionType(InventoryTransactionType.INBOUND);
                    saved.setReferenceType(InventoryReferenceType.WORK_ORDER);
                    saved.setCreatedAt(LocalDateTime.of(2026,10,28,20,0));return insertRows;
                case "selectReturnedQuantitiesForUpdate":
                    assertArrayEquals(new Object[]{7L,100L},a);assertEquals(List.of("log"),writes);
                    return returned; // 包括本事务刚插入的退料，表示锁定当前读结果。
                case "increaseStock":
                    assertArrayEquals(new Object[]{7L,10L,20L,new BigDecimal("2.000")},a);
                    writes.add("stock");if(failStock)throw new DataAccessResourceFailureException("test DB failure");return stockRows;
                case "increaseReturnedQuantity":
                    assertArrayEquals(new Object[]{7L,30L,20L,new BigDecimal("2.000")},a);
                    writes.add("returned");return partRows;
                case "findById":
                    assertArrayEquals(new Object[]{7L,200L},a);return missingSaved?null:saved;
                default:throw new AssertionError(m.getName());
            }
        });
        ReflectionTestUtils.setField(service,"currentAccessService",access);
        ReflectionTestUtils.setField(service,"stockReturnMapper",mapper);return service;
    }
    private void rejects(int expected) { rejects(expected,"return-1",request()); }
    private void rejects(int expected,String key,StockReturnCreateDto request) {
        var error=assertThrows(ResponseStatusException.class,()->service().returnStock(key,request));
        assertEquals(expected,error.getStatusCode().value());
    }
    @Test void 首次退料写关联原领料的正数入库并增加原仓库和工单已退量() {
        var result=service().returnStock("return-1",request());assertNotNull(result);
        assertEquals(200L,result.getId());assertEquals(7L,result.getTenantId());assertEquals(1L,result.getCreatedBy());
        assertEquals(10L,result.getWarehouseId());assertEquals(20L,result.getSparePartId());assertEquals(30L,result.getWorkOrderId());
        assertEquals(100L,ReflectionTestUtils.getField(result,"issueTransactionId"));
        assertEquals(InventoryTransactionType.INBOUND,result.getTransactionType());
        assertEquals(InventoryReferenceType.WORK_ORDER,result.getReferenceType());
        assertEquals(new BigDecimal("2.000"),result.getQuantity());assertNotNull(result.getCreatedAt());
        assertEquals("return-1",result.getRequestId());assertTrue(result.getTransactionNo().length()<=40);
        assertEquals(List.of("log","stock","returned"),writes);
    }
    @Test void 提交验收或完成后仍可退料() {
        for(var value:List.of(WorkOrderStatus.AWAITING_ACCEPTANCE,WorkOrderStatus.COMPLETED)) {
            status=value;writes.clear();assertNotNull(service().returnStock("return-1",request()));
        }
    }
    @Test void 重复请求直接返回原流水即使资源已停用且不再写库() {
        existing=completed();warehouse=null;original=null;
        var r=request();r.setQuantity(new BigDecimal("2"));
        assertEquals("RET-existing",service().returnStock("return-1",r).getTransactionNo());
        assertTrue(writes.isEmpty());assertEquals(0,resourceReads);
    }
    @Test void 原请求内容改变或其他操作人不能复用请求号() {
        existing=completed();existing.setCreatedBy(2L);rejects(409);
        existing=completed();existing.setNote("different");rejects(409);
        existing=completed();ReflectionTestUtils.setField(existing,"issueTransactionId",101L);rejects(409);
        existing=completed();existing.setQuantity(new BigDecimal("3"));rejects(409);
        assertTrue(writes.isEmpty());
    }
    @Test void 领料或普通入库请求号不能冒充退料成功() {
        existing=source();existing.setRequestId("return-1");rejects(409);
        existing=completed();existing.setReferenceType(InventoryReferenceType.MANUAL);rejects(409);
        assertTrue(writes.isEmpty());
    }
    @Test void 并发重复请求优先重放不因原领料已退满而报超退() {
        duplicate=true;concurrent=completed();returned=List.of(new BigDecimal("5"));
        assertEquals("RET-existing",service().returnStock("return-1",request()).getTransactionNo());
        assertEquals(1,currentReads);assertEquals(List.of("log"),writes);
    }
    @Test void 并发重放不同内容或其他唯一键冲突返回409且不加库存() {
        duplicate=true;rejects(409);
        concurrent=completed();concurrent.setCreatedBy(2L);rejects(409);
        concurrent=completed();concurrent.setNote("different");rejects(409);
        assertFalse(writes.contains("stock"));
    }
    @Test void 多次退回累计可以等于原领用数量() {
        returned=List.of(new BigDecimal("3.000"),new BigDecimal("2.000"));
        assertNotNull(service().returnStock("return-1",request()));
    }
    @Test void 累计退回超过原领用必须失败且不增加库存用料() {
        returned=List.of(new BigDecimal("4.000"),new BigDecimal("2.000"));rejects(409);
        assertEquals(List.of("log"),writes);
    }
    @Test void 原流水不存在或属于其他企业返回404() {
        original=null;rejects(404);assertTrue(writes.isEmpty());
    }
    @Test void 原流水必须是完整的工单出库() {
        original.setTransactionType(InventoryTransactionType.INBOUND);rejects(409);
        original=source();original.setReferenceType(InventoryReferenceType.MANUAL);rejects(409);
        original=source();original.setWarehouseId(null);rejects(409);
        original=source();original.setWorkOrderId(null);rejects(409);
        assertTrue(writes.isEmpty());
    }
    @Test void 仓库厂区或备件不可用时拒绝写库() {
        warehouse=null;rejects(404);
        warehouse=new StockInWarehouseCheckVO(10L,false,40L,true);rejects(409);
        warehouse=new StockInWarehouseCheckVO(10L,true,null,null);rejects(409);
        warehouse=new StockInWarehouseCheckVO(10L,true,40L,true);partEnabled=null;rejects(404);
        partEnabled=false;rejects(409);assertTrue(writes.isEmpty());
    }
    @Test void 非负责人或待处理工单不能新建退料() {
        assignee=2L;rejects(403);assignee=null;rejects(403);
        assignee=1L;status=WorkOrderStatus.PENDING;rejects(409);assertTrue(writes.isEmpty());
    }
    @Test void 原流水锁定后归属资源变化必须重试而非退入旧仓库() {
        locked=source();locked.setWarehouseId(11L);rejects(409);assertTrue(writes.isEmpty());
    }
    @Test void 库存溢出或余额记录缺失时不能返回成功() {
        stockRows=0;rejects(409);assertEquals(List.of("log","stock"),writes);
    }
    @Test void 工单累计已退超出已领量或用料行缺失时必须失败() {
        partRows=0;rejects(409);assertEquals(List.of("log","stock","returned"),writes);
    }
    @Test void 数据库写入异常继续抛出交由事务回滚() {
        failStock=true;assertThrows(DataAccessResourceFailureException.class,()->service().returnStock("return-1",request()));
        assertFalse(writes.contains("returned"));
    }
    @Test void 流水未插入未回填主键或重查失败均不能伪装成功() {
        insertRows=0;rejects(500);insertRows=1;missingId=true;rejects(500);
        assertFalse(writes.contains("stock"));
        writes.clear();missingId=false;missingSaved=true;rejects(500);
    }
    @Test void 非法参数和请求号必须在数据库调用前拒绝() {
        for(String key:Arrays.asList(null,""," ","with space","a".repeat(65),"中文"))rejects(400,key,request());
        rejects(400,"return-1",null);
        var r=request();r.setIssueTransactionId(null);rejects(400,"return-1",r);
        r.setIssueTransactionId(0L);rejects(400,"return-1",r);
        for(BigDecimal q:Arrays.asList(null,BigDecimal.ZERO,new BigDecimal("-1"),new BigDecimal("0.0001"),
                new BigDecimal("100000000000"),new BigDecimal("2.0000"))) {
            r=request();r.setQuantity(q);rejects(400,"return-1",r);
        }
        r=request();r.setNote("x".repeat(501));rejects(400,"return-1",r);assertTrue(writes.isEmpty());
    }
}
