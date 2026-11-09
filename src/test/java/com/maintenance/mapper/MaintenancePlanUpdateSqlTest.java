package com.maintenance.mapper;

import com.equipops.domain.dto.maintenance.MaintenancePlanUpdateDto;
import com.equipops.domain.enums.MaintenancePlanType;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 实际SQL及JDBC绑定检查，不连接数据库。 */
class MaintenancePlanUpdateSqlTest {
    private Configuration config() throws Exception {
        var config = new Configuration(); String resource = "mapper/MaintenancePlanMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input); new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        return config;
    }
    @Test void 编辑读取使用当前读且固定企业和ID() throws Exception {
        var bound = config().getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.selectPlanDetailForUpdate")
                .getBoundSql(Map.of("tenantId", 7L, "id", 100L));
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.contains("WHERE p.tenant_id = ? AND p.id = ?")); assertTrue(sql.endsWith("FOR UPDATE"));
    }
    @Test void SQL只更新可编辑字段且准确绑定企业ID和枚举() throws Exception {
        var config = config(); var statement = config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.updatePlan");
        var request = new MaintenancePlanUpdateDto(); request.setName("新计划"); request.setEquipmentId(21L);
        request.setPlanType(MaintenancePlanType.MAINTENANCE); request.setContent("润滑"); request.setCycleDays(30);
        request.setNextDueDate(LocalDate.of(2099,1,1)); request.setAssigneeUserId(31L);
        var method = MaintenancePlanMapper.class.getMethod("updatePlan", Long.class, Long.class, MaintenancePlanUpdateDto.class, Long.class);
        Object params = new ParamNameResolver(config, method).getNamedParams(new Object[]{7L, 100L, request, 41L});
        var bound = statement.getBoundSql(params); String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.endsWith("WHERE tenant_id = ? AND id = ?"));
        String set = sql.substring(sql.indexOf("SET"), sql.indexOf("WHERE"));
        for (String protectedField : new String[]{"tenant_id", "enabled", "created_by", "created_at", "updated_at"}) assertFalse(set.contains(protectedField));
        Map<Integer,Object> values = new HashMap<>();
        PreparedStatement jdbc = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p,m,a) -> {
            if (m.getName().startsWith("set")) values.put((Integer)a[0], a[1]); return null;
        });
        new DefaultParameterHandler(statement, params, bound).setParameters(jdbc);
        assertEquals(Map.of(1,"新计划",2,21L,3,41L,4,"MAINTENANCE",5,"润滑",6,30,7,LocalDate.of(2099,1,1),8,31L,9,7L,10,100L),values);
    }
    @Test void 编辑资源校验均使用当前读且保留企业与启用条件() throws Exception {
        var config = config();
        for (String name : new String[]{"selectTenantEquipmentForUpdate", "selectTenantSiteForUpdate", "selectEnabledAssigneeForUpdate"}) {
            var params = Map.of("tenantId", 7L, "equipmentId", 20L, "siteId", 40L, "userId", 30L);
            var sql = config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper." + name)
                    .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
            assertTrue(sql.endsWith("FOR UPDATE")); assertTrue(sql.contains("tenant_id = ?"));
            if (name.equals("selectEnabledAssigneeForUpdate")) assertTrue(sql.contains("tm.enabled = TRUE AND u.enabled = TRUE"));
        }
    }

}
