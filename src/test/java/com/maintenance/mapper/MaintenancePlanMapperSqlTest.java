package com.maintenance.mapper;

import com.equipops.domain.entity.MaintenancePlanEntity;
import com.equipops.domain.enums.MaintenancePlanType;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证实际XML解析、企业范围及JDBC绑定；不执行SQL、不连接数据库。 */
class MaintenancePlanMapperSqlTest {
    private Configuration config() throws Exception {
        var config = new Configuration();
        String resource = "mapper/MaintenancePlanMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        return config;
    }

    private String sql(String method, Map<String, Object> parameters) throws Exception {
        return config().getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper." + method)
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
    }

    @Test void 设备厂区负责人查询均限定企业且负责人检查两个启用状态() throws Exception {
        assertTrue(sql("selectTenantEquipment", Map.of("tenantId", 7L, "equipmentId", 20L))
                .contains("WHERE tenant_id = ? AND id = ?"));
        assertTrue(sql("selectTenantSite", Map.of("tenantId", 7L, "siteId", 40L))
                .contains("WHERE tenant_id = ? AND id = ?"));
        String assignee = sql("countEnabledAssignee", Map.of("tenantId", 7L, "userId", 30L));
        assertTrue(assignee.contains("u.id = tm.user_id"));
        assertTrue(assignee.contains("tm.tenant_id = ? AND tm.user_id = ?"));
        assertTrue(assignee.contains("tm.enabled = TRUE AND u.enabled = TRUE"));
    }

    @Test void 回查不能跨企业关联设备或负责人且不因停用隐藏计划() throws Exception {
        String detail = sql("selectPlanDetail", Map.of("tenantId", 7L, "id", 100L));
        assertTrue(detail.contains("e.tenant_id = p.tenant_id AND e.id = p.equipment_id"));
        assertTrue(detail.contains("tm.tenant_id = p.tenant_id AND tm.user_id = p.assignee_user_id"));
        assertTrue(detail.contains("u.id = tm.user_id"));
        assertTrue(detail.contains("WHERE p.tenant_id = ? AND p.id = ?"));
        assertFalse(detail.contains("enabled ="));
    }

    @Test void 插入将枚举写成名称并回填实体主键() throws Exception {
        var config = config();
        var statement = config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.insertPlan");
        var plan = new MaintenancePlanEntity();
        plan.setTenantId(7L); plan.setSiteId(40L); plan.setEquipmentId(20L);
        plan.setName("每周巡检"); plan.setPlanType(MaintenancePlanType.INSPECTION);
        plan.setContent("检查异响"); plan.setCycleDays(7);
        plan.setNextDueDate(LocalDate.of(2099, 1, 1)); plan.setAssigneeUserId(30L); plan.setCreatedBy(10L);
        var method = MaintenancePlanMapper.class.getMethod("insertPlan", MaintenancePlanEntity.class);
        Object parameters = new ParamNameResolver(config, method).getNamedParams(new Object[]{plan});
        var bound = statement.getBoundSql(parameters);
        assertArrayEquals(new String[]{"plan.id"}, statement.getKeyProperties());
        assertEquals(List.of("plan.tenantId", "plan.siteId", "plan.equipmentId", "plan.name",
                "plan.planType", "plan.content", "plan.cycleDays", "plan.nextDueDate",
                "plan.assigneeUserId", "plan.enabled", "plan.createdBy"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        Map<Integer, Object> values = new HashMap<>();
        PreparedStatement jdbc = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (p, m, args) -> {
                    if (m.getName().startsWith("set")) values.put((Integer) args[0], args[1]);
                    return null;
                });
        new DefaultParameterHandler(statement, parameters, bound).setParameters(jdbc);
        assertEquals(7L, values.get(1)); assertEquals(40L, values.get(2));
        assertEquals("INSPECTION", values.get(5)); assertEquals(7, values.get(7));
        assertEquals(LocalDate.of(2099, 1, 1), values.get(8));
        assertEquals(30L, values.get(9)); assertEquals(true, values.get(10)); assertEquals(10L, values.get(11));
    }
}
