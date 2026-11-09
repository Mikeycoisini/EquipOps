package com.workorder.mapper;

import com.equipops.domain.dto.workorder.WorkOrderPageQueryDto;
import com.equipops.domain.entity.WorkOrderLogEntity;
import com.equipops.domain.enums.WorkOrderPriority;
import com.equipops.domain.enums.WorkOrderStatus;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 使用 MyBatis 实际解析动态 SQL，不连接或写入数据库。 */
class WorkOrderMapperSqlTest {
    private BoundSql sql(String resource, String statement, Object parameters) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("mapper/" + resource)) {
            assertNotNull(input, "工单查询和更新应提供对应的 Mapper XML");
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(statement).getBoundSql(parameters);
    }

    private String text(BoundSql sql) {
        return sql.getSql().replaceAll("\\s+", " ").trim();
    }

    @Test
    void 分页始终限定企业且关键字OR条件分组绑定() throws Exception {
        WorkOrderPageQueryDto query = new WorkOrderPageQueryDto();
        query.setStatus(WorkOrderStatus.PENDING);
        query.setPriority(WorkOrderPriority.HIGH);
        query.setEquipmentId(10L);
        query.setAssigneeUserId(20L);
        BoundSql bound = sql("WorkOrderMapper.xml", "com.workorder.mapper.WorkOrderMapper.selectWorkOrderPage",
                Map.of("tenantId", 7L, "query", query, "keyword", "' OR 1=1 --"));
        String sql = text(bound);
        assertTrue(sql.contains("WHERE tenant_id = ?"));
        assertTrue(sql.contains("AND (order_no LIKE CONCAT('%', ?, '%') OR title LIKE CONCAT('%', ?, '%'))"));
        assertTrue(sql.endsWith("ORDER BY created_at DESC, id DESC"));
        assertFalse(sql.contains("' OR 1=1 --"));
        assertEquals(List.of("tenantId", "query.status", "query.priority", "query.equipmentId",
                        "query.assigneeUserId", "keyword", "keyword"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    @Test
    void 可选条件为空时仍然按企业分页() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", 7L);
        parameters.put("query", new WorkOrderPageQueryDto());
        parameters.put("keyword", null);
        BoundSql bound = sql("WorkOrderMapper.xml", "com.workorder.mapper.WorkOrderMapper.selectWorkOrderPage", parameters);
        assertEquals(List.of("tenantId"), bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        assertFalse(text(bound).contains("LIKE"));
    }

    @Test
    void 派单仅修改负责人并保留并发与企业条件() throws Exception {
        BoundSql bound = sql("WorkOrderMapper.xml", "com.workorder.mapper.WorkOrderMapper.assignWorkOrder",
                Map.of("tenantId", 7L, "id", 10L, "assigneeUserId", 20L));
        String sql = text(bound);
        assertTrue(sql.contains("SET assignee_user_id = ? WHERE tenant_id = ? AND id = ?"));
        assertTrue(sql.contains("AND status = 'PENDING'"));
        assertTrue(sql.contains("AND assignee_user_id IS NULL"));
        assertFalse(sql.substring(sql.indexOf("SET"), sql.indexOf("WHERE")).contains("status"));
        assertEquals(List.of("assigneeUserId", "tenantId", "id"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    @Test
    void 开始维修保留企业状态和负责人条件() throws Exception {
        BoundSql bound = sql("WorkOrderMapper.xml", "com.workorder.mapper.WorkOrderMapper.startRepair",
                Map.of("tenantId", 7L, "id", 10L, "assigneeUserId", 20L));
        assertTrue(text(bound).contains("SET status = 'IN_PROGRESS' WHERE tenant_id = ? AND id = ?"));
        assertTrue(text(bound).contains("AND status = 'PENDING' AND assignee_user_id = ?"));
        assertEquals(List.of("tenantId", "id", "assigneeUserId"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    @Test
    void 合并后维修记录的Mapper方法与XML参数仍然匹配() throws Exception {
        // 从 Java 方法读取参数名，避免 XML 存在但接口方法在合并时丢失。
        var select = WorkOrderMapper.class.getMethod("selectTenantAnduserId", Long.class, Long.class);
        Object parameters = new ParamNameResolver(new Configuration(), select)
                .getNamedParams(new Object[]{7L, 10L});
        BoundSql locked = sql("WorkOrderMapper.xml", "com.workorder.mapper.WorkOrderMapper.selectTenantAnduserId",
                parameters);
        assertTrue(text(locked).contains("WHERE tenant_id = ? AND id = ? FOR UPDATE"));

        var insert = WorkOrderMapper.class.getMethod("insertWorkLog", WorkOrderLogEntity.class);
        WorkOrderLogEntity log = new WorkOrderLogEntity();
        log.setWorkOrderId(10L);
        Object logParameters = new ParamNameResolver(new Configuration(), insert)
                .getNamedParams(new Object[]{log});
        BoundSql inserted = sql("WorkOrderMapper.xml", "com.workorder.mapper.WorkOrderMapper.insertWorkLog",
                logParameters);
        assertTrue(text(inserted).startsWith("INSERT INTO work_order_logs"));
        assertEquals(List.of("log.workOrderId", "log.eventType", "log.fromStatus", "log.toStatus",
                        "log.title", "log.detail", "log.actorUserId"),
                inserted.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    @Test
    void 日志查询关联父工单限定企业并按时间和ID排序() throws Exception {
        BoundSql bound = sql("WorkOrderLogMapper.xml", "com.workorder.mapper.WorkOrderLogMapper.selectByWorkOrderId",
                Map.of("tenantId", 7L, "workOrderId", 10L));
        String sql = text(bound);
        assertTrue(sql.contains("INNER JOIN work_orders w ON w.id = l.work_order_id"));
        assertTrue(sql.contains("WHERE w.tenant_id = ? AND l.work_order_id = ?"));
        assertTrue(sql.endsWith("ORDER BY l.created_at ASC, l.id ASC"));
        assertEquals(List.of("tenantId", "workOrderId"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }
}
