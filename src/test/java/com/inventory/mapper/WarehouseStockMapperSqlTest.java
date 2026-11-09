package com.inventory.mapper;

import com.equipops.domain.dto.inventory.WarehouseStockPageQueryDto;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 由 MyBatis 实际解析 XML 和动态条件，验证发出的查询契约；不连接数据库。 */
class WarehouseStockMapperSqlTest {
    private BoundSql sql(String statement, Map<String, Object> params) throws Exception {
        var config = new Configuration();
        String resource = "mapper/WarehouseStockMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "仓库余额必须有真实 SQL 映射");
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        return config.getMappedStatement("com.inventory.mapper.WarehouseStockMapper." + statement).getBoundSql(params);
    }
    private Map<String, Object> params(WarehouseStockPageQueryDto query, String keyword) {
        Map<String, Object> result = new HashMap<>();
        result.put("tenantId", 7L);
        result.put("query", query);
        result.put("keyword", keyword);
        return result;
    }
    private String text(BoundSql bound) { return bound.getSql().replaceAll("\\s+", " ").trim(); }

    @Test void 无筛选时仍限定企业并联查同企业仓库和备件() throws Exception {
        var bound = sql("selectStockPage", params(new WarehouseStockPageQueryDto(), null));
        var text = text(bound);
        assertTrue(text.contains("FROM warehouse_stock s"));
        assertTrue(text.contains("w.id = s.warehouse_id AND w.tenant_id = s.tenant_id"));
        assertTrue(text.contains("p.id = s.spare_part_id AND p.tenant_id = s.tenant_id"));
        assertTrue(text.contains("WHERE s.tenant_id = ?"));
        assertTrue(text.contains("s.quantity < s.min_stock_quantity"));
        assertFalse(text.contains("p.stock_quantity"));
        assertFalse(text.contains("p.min_stock_quantity"));
        assertTrue(text.endsWith("ORDER BY s.warehouse_id ASC, s.spare_part_id ASC, s.id ASC"));
        assertEquals(List.of("tenantId"), bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    @Test void 所有筛选参数绑定且编号名称OR不会绕过企业() throws Exception {
        var query = new WarehouseStockPageQueryDto();
        query.setWarehouseId(10L);
        query.setSparePartId(20L);
        query.setLowStockOnly(true);
        var bound = sql("selectStockPage", params(query, "' OR 1=1 --"));
        var text = text(bound);
        assertTrue(text.contains("AND s.warehouse_id = ?"));
        assertTrue(text.contains("AND s.spare_part_id = ?"));
        assertTrue(text.contains("AND s.quantity < s.min_stock_quantity"));
        assertTrue(text.contains("AND (p.part_code LIKE CONCAT('%', ?, '%') OR p.name LIKE CONCAT('%', ?, '%'))"));
        assertFalse(text.contains("' OR 1=1 --"));
        assertEquals(List.of("tenantId", "query.warehouseId", "query.sparePartId", "keyword", "keyword"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    @Test void false低库存条件不限制结果也不隐藏停用仓库备件() throws Exception {
        var query = new WarehouseStockPageQueryDto();
        query.setLowStockOnly(false);
        var text = text(sql("selectStockPage", params(query, null)));
        assertFalse(text.substring(text.indexOf("WHERE")).contains("enabled"));
        assertFalse(text.substring(text.indexOf("WHERE")).contains("s.quantity < s.min_stock_quantity"));
    }

    @Test void 筛选对象的存在性查询同时绑定企业和对象ID() throws Exception {
        var warehouse = sql("countTenantWarehouse", Map.of("tenantId", 7L, "warehouseId", 10L));
        assertEquals("SELECT COUNT(*) FROM warehouses WHERE tenant_id = ? AND id = ?", text(warehouse));
        assertEquals(List.of("tenantId", "warehouseId"), warehouse.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        var part = sql("countTenantSparePart", Map.of("tenantId", 7L, "sparePartId", 20L));
        assertEquals("SELECT COUNT(*) FROM spare_parts WHERE tenant_id = ? AND id = ?", text(part));
    }
}
