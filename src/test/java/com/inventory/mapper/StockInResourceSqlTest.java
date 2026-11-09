package com.inventory.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 MyBatis 生成的入库资源查询及状态映射，不连接数据库。 */
class StockInResourceSqlTest {
    private Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/WarehouseStockMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    @Test void 仓库联查同企业厂区并保留缺失及停用状态() throws Exception {
        var configuration = configuration();
        String id = "com.inventory.mapper.WarehouseStockMapper.selectWarehouseForStockIn";
        assertTrue(configuration.hasStatement(id), "需要提供仓库和厂区状态的 SQL 映射");
        var statement = configuration.getMappedStatement(id);
        var bound = statement.getBoundSql(Map.of("tenantId", 7L, "warehouseId", 10L));
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.contains("LEFT JOIN sites s ON s.id = w.site_id AND s.tenant_id = w.tenant_id"));
        assertTrue(sql.contains("WHERE w.tenant_id = ? AND w.id = ?"));
        assertFalse(sql.substring(sql.indexOf("WHERE")).contains("enabled"));
        assertEquals(List.of("tenantId", "warehouseId"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        assertEquals(List.of("warehouseId:warehouse_id", "warehouseEnabled:warehouse_enabled",
                        "siteId:site_id", "siteEnabled:site_enabled"),
                statement.getResultMaps().get(0).getResultMappings().stream()
                        .map(mapping -> mapping.getProperty() + ":" + mapping.getColumn()).toList());
    }

    @Test void 备件状态查询限定企业且不把停用当成不存在() throws Exception {
        var configuration = configuration();
        String id = "com.inventory.mapper.WarehouseStockMapper.selectSparePartEnabled";
        assertTrue(configuration.hasStatement(id), "需要提供当前企业备件状态的 SQL 映射");
        var statement = configuration.getMappedStatement(id);
        var bound = statement.getBoundSql(Map.of("tenantId", 7L, "sparePartId", 20L));
        assertEquals("SELECT enabled FROM spare_parts WHERE tenant_id = ? AND id = ?",
                bound.getSql().replaceAll("\\s+", " ").trim());
        assertEquals(Boolean.class, statement.getResultMaps().get(0).getType());
        assertEquals(List.of("tenantId", "sparePartId"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }
}
