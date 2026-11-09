package com.inventory.mapper;

import com.equipops.domain.entity.InventoryTransactionEntity;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 校验实际 XML 的绑定、企业范围和条件更新契约，不执行 MySQL。 */
class StockIssueMapperSqlTest {
    private Configuration config() throws Exception {
        var c = new Configuration();
        try (var input = getClass().getClassLoader().getResourceAsStream("mapper/StockIssueMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(input,c,"mapper/StockIssueMapper.xml",c.getSqlFragments()).parse();
        }
        return c;
    }
    private org.apache.ibatis.mapping.MappedStatement statement(String method) throws Exception {
        var c=config(); var name="com.inventory.mapper.StockIssueMapper."+method;
        assertTrue(c.hasStatement(name),"缺少领料语句："+method);
        return c.getMappedStatement(name);
    }
    private Map<String,Object> params() {
        return Map.of("tenantId",7L,"warehouseId",10L,"sparePartId",20L,"workOrderId",30L,
                "quantity",new BigDecimal("2.500"),"requestId","issue-1","id",100L);
    }
    private String text(String method) throws Exception {
        return statement(method).getBoundSql(params()).getSql().replaceAll("\\s+"," ").trim();
    }
    @Test void 库存扣减必须原子更新且同时限定企业仓库备件和足额条件() throws Exception {
        var bound=statement("decreaseStock").getBoundSql(params());
        var sql=bound.getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("quantity = quantity - ?"));
        assertTrue(sql.contains("tenant_id = ? AND warehouse_id = ? AND spare_part_id = ?"));
        assertTrue(sql.contains("quantity >= ?"));
        assertEquals(List.of("quantity","tenantId","warehouseId","sparePartId","quantity"),
                bound.getParameterMappings().stream().map(p->p.getProperty()).toList());
    }
    @Test void 用料建行限定双方企业且已有行不覆盖三种数量() throws Exception {
        var sql=text("ensureWorkOrderPartRow");
        assertTrue(sql.contains("sp.tenant_id = wo.tenant_id"));
        assertTrue(sql.contains("WHERE wo.tenant_id = ? AND wo.id = ?"));
        assertTrue(sql.contains("SELECT wo.id, sp.id, 0, 0, 0"));
        assertTrue(sql.endsWith("ON DUPLICATE KEY UPDATE id = work_order_parts.id"));
    }
    @Test void 用料累计带企业归属和数值上限且不覆盖计划退回量() throws Exception {
        var sql=text("increaseIssuedQuantity");
        assertTrue(sql.contains("sp.tenant_id = wo.tenant_id"));
        assertTrue(sql.contains("SET wp.issued_quantity = wp.issued_quantity + ?"));
        assertTrue(sql.contains("wo.tenant_id = ? AND wp.work_order_id = ? AND wp.spare_part_id = ?"));
        assertTrue(sql.contains("wp.issued_quantity <= 99999999999.999 - ?"));
        assertFalse(sql.contains("planned_quantity =")); assertFalse(sql.contains("returned_quantity ="));
    }
    @Test void 流水固定出库工单类型并回填主键() throws Exception {
        var s=statement("insertStockIssue");
        var b=s.getBoundSql(Map.of("log",new InventoryTransactionEntity()));
        assertArrayEquals(new String[]{"log.id"},s.getKeyProperties());
        assertTrue(b.getSql().contains("'OUTBOUND'")); assertTrue(b.getSql().contains("'WORK_ORDER'"));
        assertEquals(List.of("log.tenantId","log.warehouseId","log.requestId","log.transactionNo",
                "log.sparePartId","log.quantity","log.workOrderId","log.createdBy","log.note"),
                b.getParameterMappings().stream().map(p->p.getProperty()).toList());
    }
    @Test void 冲突重查使用企业请求号当前读且返回查询也限定企业() throws Exception {
        assertTrue(text("findByRequestForUpdate").contains("WHERE tenant_id = ? AND request_id = ? FOR UPDATE"));
        assertTrue(text("findById").contains("WHERE tenant_id = ? AND id = ?"));
    }
}
