package com.workorder.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 日志显示名补充不得扩大企业范围，也不得隐藏历史日志。 */
class WorkOrderLogViewSqlTest {
    private Configuration config() throws Exception {
        var c=new Configuration();
        String resource="mapper/WorkOrderLogMapper.xml";
        try(var in=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in);
            new XMLMapperBuilder(in,c,resource,c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void 操作者姓名只读取当前企业关联成员且保留停用成员日志() throws Exception {
        var s=config().getMappedStatement("com.workorder.mapper.WorkOrderLogMapper.selectViewByWorkOrderId");
        var bound=s.getBoundSql(Map.of("tenantId",7L,"workOrderId",11L));
        var sql=bound.getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("WHERE w.tenant_id = ? AND l.work_order_id = ?"));
        assertTrue(sql.contains("LEFT JOIN tenant_memberships m ON m.tenant_id = w.tenant_id AND m.user_id = l.actor_user_id"));
        assertTrue(sql.contains("LEFT JOIN users u ON u.id = m.user_id"));
        assertFalse(sql.contains("enabled ="));
        assertTrue(sql.endsWith("ORDER BY l.created_at ASC, l.id ASC"));
        assertEquals(List.of("tenantId","workOrderId"),bound.getParameterMappings().stream().map(p->p.getProperty()).toList());
        var fields=s.getResultMaps().get(0).getResultMappings().stream().map(m->m.getProperty()).toList();
        assertTrue(fields.containsAll(List.of("actorName","actorUserId","detail","fromStatus","toStatus")));
    }
    @Test void 原有实体日志查询继续保留() throws Exception {
        var s=config().getMappedStatement("com.workorder.mapper.WorkOrderLogMapper.selectByWorkOrderId");
        assertEquals(com.equipops.domain.entity.WorkOrderLogEntity.class,s.getResultMaps().get(0).getType());
    }
}