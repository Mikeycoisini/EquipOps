package com.maintenance.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证锁定和更新语句的企业条件及更新范围，不执行SQL。 */
class MaintenancePlanEnabledSqlTest {
    @Test void 锁定当前企业计划且仅更新启用字段() throws Exception {
        var config = new Configuration(); String resource = "mapper/MaintenancePlanMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input); new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        var params = Map.of("tenantId", 7L, "id", 100L, "enabled", false);
        var lock = config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.selectPlanEnabledForUpdate").getBoundSql(params);
        assertEquals("SELECT enabled FROM maintenance_plans WHERE tenant_id = ? AND id = ? FOR UPDATE",
                lock.getSql().replaceAll("\\s+", " ").trim());
        var update = config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.updatePlanEnabled").getBoundSql(params);
        assertEquals("UPDATE maintenance_plans SET enabled = ? WHERE tenant_id = ? AND id = ?",
                update.getSql().replaceAll("\\s+", " ").trim());
        assertEquals(List.of("enabled", "tenantId", "id"),
                update.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }
}
