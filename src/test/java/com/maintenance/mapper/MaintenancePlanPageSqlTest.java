package com.maintenance.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.maintenance.MaintenancePlanPageQueryDto;
import com.equipops.domain.enums.MaintenancePlanType;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证XML实际生成的分页查询和JDBC绑定；不连接数据库。 */
class MaintenancePlanPageSqlTest {
    private Configuration config() throws Exception {
        var config = new Configuration(); String resource = "mapper/MaintenancePlanMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        return config;
    }
    private Object params(Configuration config, MaintenancePlanPageQueryDto query) throws Exception {
        var method = MaintenancePlanMapper.class.getMethod("selectPlanPage", Page.class, Long.class, MaintenancePlanPageQueryDto.class);
        return new ParamNameResolver(config, method).getNamedParams(new Object[]{new Page<>(1, 10), 7L, query});
    }
    private String sql(BoundSql bound) { return bound.getSql().replaceAll("\\s+", " ").trim(); }

    @Test void 默认查询始终固定企业并联查本企业设备和负责人() throws Exception {
        var config = config(); var statement = config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.selectPlanPage");
        var bound = statement.getBoundSql(params(config, new MaintenancePlanPageQueryDto()));
        String sql = sql(bound);
        assertTrue(sql.contains("e.tenant_id = p.tenant_id AND e.id = p.equipment_id"));
        assertTrue(sql.contains("tm.tenant_id = p.tenant_id AND tm.user_id = p.assignee_user_id"));
        assertTrue(sql.contains("WHERE p.tenant_id = ?"));
        assertTrue(sql.endsWith("ORDER BY p.created_at DESC, p.id DESC"));
        assertFalse(sql.contains("p.enabled ="));
        assertEquals(List.of("tenantId"), bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }
    @Test void 组合筛选均使用AND且停用状态实际绑定为false() throws Exception {
        var config = config(); MappedStatement statement = config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.selectPlanPage");
        Object params = params(config, new MaintenancePlanPageQueryDto(1, 10, "巡检", 20L, MaintenancePlanType.INSPECTION, false));
        var bound = statement.getBoundSql(params); String sql = sql(bound);
        assertTrue(sql.contains("WHERE p.tenant_id = ? AND p.name LIKE CONCAT('%', ?, '%') AND p.equipment_id = ? AND p.plan_type = ? AND p.enabled = ?"));
        assertEquals(List.of("tenantId", "query.keyword", "query.equipmentId", "query.planType", "query.enabled"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        Map<Integer, Object> values = new HashMap<>();
        PreparedStatement jdbc = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (p, m, args) -> {
                    if (m.getName().startsWith("set")) values.put((Integer) args[0], args[1]);
                    return null;
                });
        new DefaultParameterHandler(statement, params, bound).setParameters(jdbc);
        assertEquals(Map.of(1, 7L, 2, "巡检", 3, 20L, 4, "INSPECTION", 5, false), values);
    }
}
