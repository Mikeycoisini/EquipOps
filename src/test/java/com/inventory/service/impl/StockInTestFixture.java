package com.inventory.service.impl;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.inventory.StockInCreateDto;
import com.equipops.domain.entity.*;
import com.equipops.domain.enums.*;
import com.equipops.domain.vo.StockInWarehouseCheckVO;
import com.inventory.mapper.StockInMapper;
import com.inventory.mapper.WarehouseStockMapper;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** 仅用于测试的数据库边界替身；不模拟 Spring 的事务回滚，不声称验证真实 MySQL。 */
class StockInTestFixture {
    Long userId = 1L;
    Set<String> roles = Set.of("TENANT_ADMIN");
    StockInWarehouseCheckVO warehouse = new StockInWarehouseCheckVO(10L, true, 30L, true);
    Boolean partEnabled = true;
    InventoryTransactionEntity existing;
    InventoryTransactionEntity concurrent;
    InventoryTransactionEntity saved;
    int insertRows = 1;
    int increaseRows = 1;
    boolean collide;
    boolean failStock;
    boolean noSavedResult;
    boolean noGeneratedId;
    BigDecimal balance = new BigDecimal("5.000");
    final List<String> writes = new ArrayList<>();
    int resourceReads;
    int currentReads;

    StockInCreateDto request() {
        StockInCreateDto value = new StockInCreateDto();
        value.setWarehouseId(10L);
        value.setSparePartId(20L);
        value.setQuantity(new BigDecimal("2.500"));
        value.setReferenceText("采购单-1");
        value.setNote("验收入库");
        return value;
    }

    InventoryTransactionEntity completed() {
        InventoryTransactionEntity value = new InventoryTransactionEntity();
        value.setId(100L);
        value.setTenantId(7L);
        value.setWarehouseId(10L);
        value.setSparePartId(20L);
        value.setRequestId("request-1");
        value.setTransactionNo("IN-existing");
        value.setTransactionType(InventoryTransactionType.INBOUND);
        value.setReferenceType(InventoryReferenceType.MANUAL);
        value.setQuantity(new BigDecimal("2.500"));
        value.setCreatedBy(1L);
        value.setReferenceText("采购单-1");
        value.setNote("验收入库");
        value.setCreatedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        return value;
    }

    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, handler));
    }

    StockInServiceImpl service() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(7L);
        CurrentAccessService access = proxy(CurrentAccessService.class, (p, method, args) -> {
            assertEquals("requireTenantAccess", method.getName());
            return new TenantAccess(userId, tenant, 50L, roles);
        });
        InvocationHandler commands = (p, method, args) -> {
            switch (method.getName()) {
                case "findByRequest":
                    assertEquals(7L, args[0]);
                    return existing;
                case "findByRequestForUpdate":
                    assertArrayEquals(new Object[]{7L, "request-1"}, args);
                    currentReads++;
                    return concurrent;
                case "lockWarehouseForStockIn":
                case "selectWarehouseForStockIn":
                    assertArrayEquals(new Object[]{7L, 10L}, args);
                    resourceReads++;
                    return warehouse;
                case "lockSparePartEnabled":
                case "selectSparePartEnabled":
                    assertArrayEquals(new Object[]{7L, 20L}, args);
                    return partEnabled;
                case "insertStockIn":
                    writes.add("log");
                    if (collide) throw new DuplicateKeyException("simulated unique constraint");
                    if (insertRows == 1) {
                        var log = (InventoryTransactionEntity) args[0];
                        if (!noGeneratedId) log.setId(100L);
                        saved = new InventoryTransactionEntity();
                        BeanUtils.copyProperties(log, saved);
                        saved.setCreatedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
                    }
                    return insertRows;
                case "ensureStockRow":
                    assertArrayEquals(new Object[]{7L, 10L, 20L}, args);
                    writes.add("ensure");
                    return 0; // 已建账时影响零行也正常，不应当成失败。
                case "increaseStock":
                    assertArrayEquals(new Object[]{7L, 10L, 20L, new BigDecimal("2.500")}, args);
                    writes.add("increase");
                    if (failStock) throw new IllegalStateException("simulated database failure");
                    if (increaseRows == 1) balance = balance.add((BigDecimal) args[3]);
                    return increaseRows;
                case "findById":
                    assertArrayEquals(new Object[]{7L, 100L}, args);
                    return noSavedResult ? null : saved;
                default: throw new AssertionError("非预期数据库调用：" + method.getName());
            }
        };
        StockInServiceImpl service = new StockInServiceImpl();
        ReflectionTestUtils.setField(service, "currentAccessService", access);
        for (Field field : StockInServiceImpl.class.getDeclaredFields()) {
            if (field.getType() == StockInMapper.class) {
                ReflectionTestUtils.setField(service, field.getName(), proxy(StockInMapper.class, commands));
            }
            if (field.getType() == WarehouseStockMapper.class) {
                ReflectionTestUtils.setField(service, field.getName(), proxy(WarehouseStockMapper.class, commands));
            }
        }
        return service;
    }
}
