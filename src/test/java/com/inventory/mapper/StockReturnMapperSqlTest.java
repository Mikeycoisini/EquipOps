package com.inventory.mapper;

import com.equipops.domain.entity.InventoryTransactionEntity;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 检查实际映射输出的SQL和绑定契约，不连接MySQL。 */
class StockReturnMapperSqlTest {
    private Configuration config() throws Exception {
        var c=new Configuration();
        try(var in=getClass().getClassLoader().getResourceAsStream("mapper/StockReturnMapper.xml")) {
            assertNotNull(in);new XMLMapperBuilder(in,c,"mapper/StockReturnMapper.xml",c.getSqlFragments()).parse();
        }
        return c;
    }
    private org.apache.ibatis.mapping.MappedStatement statement(String name)throws Exception {
        var c=config();var id="com.inventory.mapper.StockReturnMapper."+name;
        assertTrue(c.hasStatement(id),"缺少退料SQL："+name);return c.getMappedStatement(id);
    }
    private Map<String,Object> params() {
        return Map.of("tenantId",7L,"warehouseId",10L,"sparePartId",20L,"workOrderId",30L,
                "issueTransactionId",100L,"requestId","return-1","quantity",new BigDecimal("2.000"),"id",200L);
    }
    private String sql(String name)throws Exception {
        return statement(name).getBoundSql(params()).getSql().replaceAll("\\s+"," ").trim();
    }
    @Test void 普通和冲突重查都限定企业且只有冲突查询使用当前读()throws Exception {
        assertTrue(sql("findByRequest").contains("WHERE tenant_id = ? AND request_id = ?"));
        assertFalse(sql("findByRequest").contains("FOR UPDATE"));
        assertTrue(sql("findByRequestForUpdate").endsWith("FOR UPDATE"));
        assertTrue(sql("findByRequestForUpdate").contains("WHERE tenant_id = ? AND request_id = ?"));
    }
    @Test void 原流水与已退明细按企业和原领料锁定当前读()throws Exception {
        assertTrue(sql("selectNowTenant").contains("WHERE tenant_id = ? AND id = ?"));
        assertTrue(sql("lockIssueForReturn").endsWith("FOR UPDATE"));
        assertTrue(sql("lockIssueForReturn").contains("WHERE tenant_id = ? AND id = ?"));
        var returned=sql("selectReturnedQuantitiesForUpdate");
        assertTrue(returned.startsWith("SELECT quantity"));
        assertTrue(returned.contains("tenant_id = ? AND issue_transaction_id = ?"));
        assertTrue(returned.contains("'INBOUND'"));assertTrue(returned.contains("'WORK_ORDER'"));
        assertTrue(returned.endsWith("FOR UPDATE"));
    }
    @Test void 资源锁查询保持企业范围及仓库厂区关联()throws Exception {
        var w=sql("lockWarehouseForReturn");
        assertTrue(w.contains("s.tenant_id = w.tenant_id"));assertTrue(w.endsWith("FOR UPDATE"));
        assertTrue(w.contains("WHERE w.tenant_id = ? AND w.id = ?"));
        assertTrue(sql("lockSparePartEnabled").contains("WHERE tenant_id = ? AND id = ? FOR UPDATE"));
        assertTrue(sql("lockWorkOrderForReturn").contains("WHERE tenant_id = ? AND id = ? FOR UPDATE"));
    }
    @Test void 退料流水固定正向入库类型且写入原领料关联并回填主键()throws Exception {
        var s=statement("insertStockReturn");var b=s.getBoundSql(Map.of("log",new InventoryTransactionEntity()));
        assertArrayEquals(new String[]{"log.id"},s.getKeyProperties());
        assertTrue(b.getSql().contains("'INBOUND'"));assertTrue(b.getSql().contains("'WORK_ORDER'"));
        String sql=b.getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("FROM inventory_transactions src"));
        assertTrue(sql.contains("src.tenant_id, src.warehouse_id"));
        assertTrue(sql.contains("src.spare_part_id"));
        assertTrue(sql.contains("src.work_order_id, src.id"));
        assertTrue(sql.contains("CONCAT('RET-', REPLACE(UUID(), '-', ''))"));
        assertTrue(sql.contains("WHERE src.tenant_id = ? AND src.id = ?"));
        assertTrue(sql.contains("src.transaction_type = 'OUTBOUND' AND src.reference_type = 'WORK_ORDER'"));
        assertEquals(List.of("log.requestId","log.quantity","log.createdBy","log.note",
                "log.tenantId","log.issueTransactionId"),
                b.getParameterMappings().stream().map(p->p.getProperty()).toList());
    }
    @Test void 加库存使用原子加法和数值上限且不创建余额()throws Exception {
        var sql=sql("increaseStock");assertTrue(sql.startsWith("UPDATE warehouse_stock"));
        assertTrue(sql.contains("quantity = quantity + ?"));
        assertTrue(sql.contains("tenant_id = ? AND warehouse_id = ? AND spare_part_id = ?"));
        assertTrue(sql.contains("quantity <= 99999999999.999 - ?"));
    }
    @Test void 工单已退量不超过已领且不更改已领和计划量()throws Exception {
        var sql=sql("increaseReturnedQuantity");
        assertTrue(sql.contains("sp.tenant_id = wo.tenant_id"));
        assertTrue(sql.contains("SET wp.returned_quantity = wp.returned_quantity + ?"));
        assertTrue(sql.contains("wo.tenant_id = ? AND wp.work_order_id = ? AND wp.spare_part_id = ?"));
        assertTrue(sql.contains("wp.returned_quantity <= wp.issued_quantity - ?"));
        assertFalse(sql.contains("issued_quantity ="));assertFalse(sql.contains("planned_quantity ="));
        assertTrue(sql("findById").contains("WHERE tenant_id = ? AND id = ?"));
    }
}
