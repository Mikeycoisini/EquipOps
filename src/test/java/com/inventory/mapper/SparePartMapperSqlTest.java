package com.inventory.mapper;

import com.equipops.domain.entity.SparePartEntity;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 校验 XML 生成的真实 SQL 和 JDBC 参数，不连接或修改数据库。 */
class SparePartMapperSqlTest {
    private Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/SparePartMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "备件目录更新应提供对应的 Mapper XML");
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private Object parameters(Configuration configuration) throws Exception {
        SparePartEntity part = new SparePartEntity();
        part.setId(99L);
        part.setTenantId(999L); // 实体内的企业值不得替代可信 tenantId 参数。
        part.setPartCode("TEST-001");
        part.setName("测试备件");
        part.setStockQuantity(new BigDecimal("777")); // 此字段绝不能出现在更新参数中。
        var method = SparePartMapper.class.getMethod("updateCatalog", Long.class, SparePartEntity.class);
        return new ParamNameResolver(configuration, method).getNamedParams(new Object[]{7L, part});
    }

    @Test
    void 只更新目录字段并同时限定企业与ID() throws Exception {
        Configuration configuration = configuration();
        MappedStatement statement = configuration.getMappedStatement("com.inventory.mapper.SparePartMapper.updateCatalog");
        BoundSql query = statement.getBoundSql(parameters(configuration));
        String sql = query.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.contains("WHERE tenant_id = ? AND id = ?"));
        String setClause = sql.substring(sql.indexOf("SET"), sql.indexOf("WHERE"));
        assertFalse(setClause.matches("(?s).*\\bstock_quantity\\s*=.*"));
        assertFalse(setClause.contains("tenant_id"));
        assertFalse(setClause.contains("created_at"));
        assertFalse(setClause.contains("updated_at"));
        assertEquals(List.of("part.partCode", "part.name", "part.spec", "part.category", "part.location",
                        "part.minStockQuantity", "part.unit", "part.enabled", "tenantId", "part.id"),
                query.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    @Test
    void 可空字段会显式绑定SQL空值() throws Exception {
        Configuration configuration = configuration();
        Object parameters = parameters(configuration);
        MappedStatement statement = configuration.getMappedStatement("com.inventory.mapper.SparePartMapper.updateCatalog");
        BoundSql query = statement.getBoundSql(parameters);
        Map<Integer, Object> values = new HashMap<>();
        Map<Integer, Integer> nullTypes = new HashMap<>();
        PreparedStatement jdbc = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setNull")) nullTypes.put((Integer) args[0], (Integer) args[1]);
                    else if (method.getName().startsWith("set")) values.put((Integer) args[0], args[1]);
                    return null;
                });
        new DefaultParameterHandler(statement, parameters, query).setParameters(jdbc);
        assertEquals(Map.of(3, Types.VARCHAR, 4, Types.VARCHAR, 5, Types.VARCHAR), nullTypes);
        assertEquals(7L, values.get(9));
        assertEquals(99L, values.get(10));
    }
}
