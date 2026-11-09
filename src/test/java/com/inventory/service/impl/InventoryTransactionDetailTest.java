package com.inventory.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.vo.InventoryTransactionVO;
import com.inventory.mapper.InventoryTransactionMapper;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 验证详情权限边界、404语义和真实XML绑定，不连接数据库。 */
class InventoryTransactionDetailTest {
    private boolean denied;
    private int queryCalls;
    private InventoryTransactionVO record;

    private InventoryTransactionServiceImpl service() {
        var loader=getClass().getClassLoader();
        var access=(CurrentAccessService)Proxy.newProxyInstance(loader,new Class<?>[]{CurrentAccessService.class},(p,m,args)->{
            assertEquals("requireActiveTenantId",m.getName());
            if(denied)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"成员已失效");
            return 7L;
        });
        var mapper=(InventoryTransactionMapper)Proxy.newProxyInstance(loader,new Class<?>[]{InventoryTransactionMapper.class},(p,m,args)->{
            assertEquals("selectDetailWithDetails",m.getName(),"详情查询不能写库或退回全量查询");
            assertArrayEquals(new Object[]{7L,21L},args);
            queryCalls++;
            return record;
        });
        return new InventoryTransactionServiceImpl(access,mapper,null,null,null);
    }

    @Test void 无效流水ID返回400且不查数据库() {
        for(Long id:new Long[]{null,0L,-1L}) {
            var error=assertThrows(ResponseStatusException.class,()->service().getTransactionDetail(id));
            assertEquals(400,error.getStatusCode().value());
        }
        assertEquals(0,queryCalls);
    }
    @Test void 成员失效时拒绝查询() {
        denied=true;
        var error=assertThrows(ResponseStatusException.class,()->service().getTransactionDetail(21L));
        assertEquals(403,error.getStatusCode().value());
        assertEquals(0,queryCalls);
    }
    @Test void 不存在或不属于本企业的流水统一返回404() {
        var error=assertThrows(ResponseStatusException.class,()->service().getTransactionDetail(21L));
        assertEquals(404,error.getStatusCode().value());
        assertEquals(1,queryCalls);
    }
    @Test void 返回详情保留退料原领料关联及数量精度() {
        record=new InventoryTransactionVO();
        record.setId(21L);record.setTenantId(7L);record.setIssueTransactionId(12L);
        record.setQuantity(new BigDecimal("1.125"));record.setWarehouseName("默认仓库");
        record.setPartName("轴承");record.setWorkOrderNo("WO-001");
        var result=service().getTransactionDetail(21L);
        assertSame(record,result);
        assertEquals(12L,result.getIssueTransactionId());
        assertEquals(new BigDecimal("1.125"),result.getQuantity());
        assertEquals(1,queryCalls);
    }
    @Test void 历史流水缺少关联资料仍可返回() {
        record=new InventoryTransactionVO();record.setId(21L);
        var result=service().getTransactionDetail(21L);
        assertNull(result.getWarehouseName());assertNull(result.getWorkOrderNo());
        assertEquals(21L,result.getId());
    }
    @Test void 详情XML限定企业和流水且每个关联均按企业隔离() throws Exception {
        var config=new Configuration();
        String resource="mapper/InventoryTransactionMapper.xml";
        try(var in=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in);
            new XMLMapperBuilder(in,config,resource,config.getSqlFragments()).parse();
        }
        var statement=config.getMappedStatement("com.inventory.mapper.InventoryTransactionMapper.selectDetailWithDetails");
        var bound=statement.getBoundSql(Map.of("tenantId",7L,"id",21L));
        var sql=bound.getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("WHERE t.tenant_id = ? AND t.id = ?"));
        assertTrue(sql.contains("LEFT JOIN warehouses w ON w.id = t.warehouse_id AND w.tenant_id = t.tenant_id"));
        assertTrue(sql.contains("LEFT JOIN spare_parts p ON p.id = t.spare_part_id AND p.tenant_id = t.tenant_id"));
        assertTrue(sql.contains("LEFT JOIN work_orders wo ON wo.id = t.work_order_id AND wo.tenant_id = t.tenant_id"));
        assertFalse(sql.contains("enabled ="),"停用资源仍可追溯历史流水");
        assertEquals(List.of("tenantId","id"),bound.getParameterMappings().stream().map(p->p.getProperty()).toList());
        assertTrue(statement.getResultMaps().get(0).getResultMappings().stream()
                .anyMatch(m->m.getProperty().equals("issueTransactionId")));
    }
}