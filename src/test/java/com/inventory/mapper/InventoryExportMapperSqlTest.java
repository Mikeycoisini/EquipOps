package com.inventory.mapper;

import com.equipops.domain.entity.InventoryExportTaskEntity;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 解析真实XML与跨Mapper片段；只验证绑定与SQL约束，不冒充真实MySQL执行。 */
class InventoryExportMapperSqlTest {
    Configuration config() throws Exception {
        var config=new Configuration();
        for(var name:List.of("InventoryTransactionMapper","InventoryExportTaskMapper")) {
            var resource="mapper/"+name+".xml";
            try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input); new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
            }
        }
        return config;
    }
    @Test void 所有后台SQL均存在且绑定参数可解析() throws Exception {
        var config=config();
        var task=InventoryExportTaskEntity.builder().id(1L).tenantId(7L).createdBy(2L).upperBoundId(200L)
                .warehouseId(3L).sparePartId(4L).workOrderId(5L).build();
        var params=new HashMap<String,Object>(); params.put("task",task); params.put("lastId",0L); params.put("query",new com.equipops.domain.dto.inventory.InventoryExportPageQueryDto());
        for(var method:InventoryExportTaskMapper.class.getDeclaredMethods()) {
            var stmt=config.getMappedStatement("com.inventory.mapper.InventoryExportTaskMapper."+method.getName());
            assertFalse(stmt.getBoundSql(params).getSql().isBlank());
        }
        String batch=config.getMappedStatement("com.inventory.mapper.InventoryExportTaskMapper.selectExportBatch").getBoundSql(params).getSql().replaceAll("\\s+"," ");
        assertTrue(batch.contains("t.tenant_id = ?")); assertTrue(batch.contains("t.id > ? AND t.id <= ?"));
        assertTrue(batch.contains("ORDER BY t.id ASC LIMIT 1000")); assertTrue(batch.contains("w.tenant_id = t.tenant_id"));
    }
    @Test void 发布和续租不能复活过期租约且终态需要令牌与权限() throws Exception {
        var config=config(); var task=new InventoryExportTaskEntity();
        String publish=config.getMappedStatement("com.inventory.mapper.InventoryExportTaskMapper.publishSuccess").getBoundSql(Map.of("task",task)).getSql().replaceAll("\\s+"," ");
        for(var condition:List.of("tenant_id = ?","created_by = ?","owner_instance_id = ?","run_token = ?","lease_until > CURRENT_TIMESTAMP(6)","status = 'RUNNING'","deadline_at > CURRENT_TIMESTAMP(6)","u.enabled = 1 AND m.enabled = 1 AND te.enabled = 1")) assertTrue(publish.contains(condition),condition);
        String renew=config.getMappedStatement("com.inventory.mapper.InventoryExportTaskMapper.renewOwnedLeases").getBoundSql(Map.of("task",task)).getSql();
        assertTrue(renew.contains("lease_until > CURRENT_TIMESTAMP(6)")); assertTrue(renew.contains("deadline_at > CURRENT_TIMESTAMP(6)"));
        assertTrue(renew.contains("run_token = ?"));
        String failure=config.getMappedStatement("com.inventory.mapper.InventoryExportTaskMapper.finishUnsuccessful").getBoundSql(Map.of("task",task)).getSql().replaceAll("\\s+"," ");
        assertTrue(failure.contains("WHEN deadline_at <= CURRENT_TIMESTAMP(6) THEN 'TIMEOUT'"),"worker先发现超时也必须记录TIMEOUT");
    }
    @Test void 列表状态可选且SQL总是限制企业和本人并只返回公开字段() throws Exception {
        var config=config();var query=new com.equipops.domain.dto.inventory.InventoryExportPageQueryDto();
        var stmt=config.getMappedStatement("com.inventory.mapper.InventoryExportTaskMapper.selectOwnTaskPage");
        var params=Map.of("tenantId",7L,"userId",4L,"query",query);
        String sql=stmt.getBoundSql(params).getSql().replaceAll("\\s+"," ");
        assertTrue(sql.contains("WHERE tenant_id = ? AND created_by = ?"));assertFalse(sql.contains("AND status = ?"));
        assertTrue(sql.contains("ORDER BY created_at DESC, id DESC"));
        for(var field:List.of("storage_key","owner_instance_id","run_token","lease_until","deadline_at"))assertFalse(sql.contains(field));
        query.setStatus(com.equipops.domain.enums.InventoryExportStatus.FAILED);
        assertTrue(stmt.getBoundSql(params).getSql().contains("AND status = ?"));
    }
}
