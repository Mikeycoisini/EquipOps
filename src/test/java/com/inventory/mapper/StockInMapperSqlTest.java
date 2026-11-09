package com.inventory.mapper;

import com.equipops.domain.entity.InventoryTransactionEntity;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 检查实际 MyBatis 语句、参数绑定和生成主键契约，不连接数据库。 */
class StockInMapperSqlTest {
    private Configuration config() throws Exception {
        var configuration = new Configuration();
        String resource = "mapper/StockInMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "入库需要完整 XML 映射");
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
    private org.apache.ibatis.mapping.BoundSql sql(String name, Map<String, Object> params) throws Exception {
        return config().getMappedStatement("com.inventory.mapper.StockInMapper." + name).getBoundSql(params);
    }
    private String text(org.apache.ibatis.mapping.BoundSql sql) { return sql.getSql().replaceAll("\\s+", " ").trim(); }

    @Test void 请求查询限定企业且并发冲突查询使用当前读() throws Exception {
        var params = Map.<String,Object>of("tenantId", 7L, "requestId", "request-1");
        var fast = sql("findByRequest", params);
        var current = sql("findByRequestForUpdate", params);
        assertTrue(text(fast).contains("WHERE tenant_id = ? AND request_id = ?"));
        assertFalse(text(fast).contains("FOR UPDATE"));
        assertTrue(text(current).endsWith("FOR UPDATE"));
        assertEquals(List.of("tenantId", "requestId"), current.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    @Test void 仓库厂区和备件检查保留企业限制并加行锁() throws Exception {
        var warehouse = text(sql("lockWarehouseForStockIn", Map.of("tenantId", 7L, "warehouseId", 10L)));
        assertTrue(warehouse.contains("LEFT JOIN sites s ON s.id = w.site_id AND s.tenant_id = w.tenant_id"));
        assertTrue(warehouse.contains("WHERE w.tenant_id = ? AND w.id = ?"));
        assertTrue(warehouse.endsWith("FOR UPDATE"));
        assertEquals("SELECT enabled FROM spare_parts WHERE tenant_id = ? AND id = ? FOR UPDATE",
                text(sql("lockSparePartEnabled", Map.of("tenantId", 7L, "sparePartId", 20L))));
    }

    @Test void 入库流水固定交易类型并回填主键() throws Exception {
        var statement = config().getMappedStatement("com.inventory.mapper.StockInMapper.insertStockIn");
        var bound = statement.getBoundSql(Map.of("log", new InventoryTransactionEntity()));
        assertArrayEquals(new String[]{"log.id"}, statement.getKeyProperties());
        assertTrue(text(bound).contains("'INBOUND'"));
        assertTrue(text(bound).contains("'MANUAL'"));
        assertEquals(List.of("log.tenantId", "log.warehouseId", "log.requestId", "log.transactionNo",
                        "log.sparePartId", "log.quantity", "log.referenceText", "log.createdBy", "log.note"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    @Test void 建账不覆盖余额或阈值且加量使用数值上限条件() throws Exception {
        var params = Map.<String,Object>of("tenantId", 7L, "warehouseId", 10L, "sparePartId", 20L,
                "quantity", new BigDecimal("2.500"));
        var ensure = text(sql("ensureStockRow", params));
        assertTrue(ensure.contains("VALUES (?, ?, ?, 0, 0)"));
        assertTrue(ensure.endsWith("ON DUPLICATE KEY UPDATE id = id"));
        var bound = sql("increaseStock", params);
        assertEquals("UPDATE warehouse_stock SET quantity = quantity + ? WHERE tenant_id = ? "
                        + "AND warehouse_id = ? AND spare_part_id = ? AND quantity <= 99999999999.999 - ?",
                text(bound));
        assertEquals(List.of("quantity", "tenantId", "warehouseId", "sparePartId", "quantity"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }
}
