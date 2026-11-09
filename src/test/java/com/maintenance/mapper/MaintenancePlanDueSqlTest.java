package com.maintenance.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.maintenance.MaintenancePlanPageQueryDto;
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

/** 验证实际生成SQL的到期边界、本期排除及筛选组合，不连接数据库。 */
class MaintenancePlanDueSqlTest {
    private Configuration config() throws Exception {
        var config=new Configuration();String resource="mapper/MaintenancePlanMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
        }
        return config;
    }
    private Object params(Configuration config,MaintenancePlanPageQueryDto query) throws Exception {
        var method=MaintenancePlanMapper.class.getMethod("selectDuePlanPage",Page.class,Long.class,MaintenancePlanPageQueryDto.class,LocalDate.class);
        return new ParamNameResolver(config,method).getNamedParams(new Object[]{new Page<>(1,10),7L,query,LocalDate.of(2026,10,30)});
    }
    @Test void 启用且今天以前到期并排除本企业本计划本期已登记记录() throws Exception {
        var config=config();var query=new MaintenancePlanPageQueryDto();query.setDueOnly(true);
        var bound=config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.selectDuePlanPage").getBoundSql(params(config,query));
        String sql=bound.getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("WHERE p.tenant_id = ?"));
        assertTrue(sql.contains("p.enabled = TRUE AND p.next_due_date <= ?"));
        assertTrue(sql.contains("NOT EXISTS ( SELECT 1 FROM maintenance_executions x WHERE x.tenant_id = p.tenant_id AND x.plan_id = p.id AND x.scheduled_date = p.next_due_date )"));
        assertTrue(sql.endsWith("ORDER BY p.next_due_date ASC, p.id DESC"));
        assertEquals(List.of("tenantId","today"),bound.getParameterMappings().stream().map(p->p.getProperty()).toList());
    }
    @Test void 保留原筛选条件并准确绑定日期且停用筛选不会覆盖待办的启用条件() throws Exception {
        var config=config();var query=new MaintenancePlanPageQueryDto(1,10,"巡检",20L,MaintenancePlanType.INSPECTION,false);query.setDueOnly(true);
        var statement=config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.selectDuePlanPage");
        Object params=params(config,query);var bound=statement.getBoundSql(params);
        String sql=bound.getSql().replaceAll("\\s+"," ");
        assertTrue(sql.contains("AND p.name LIKE CONCAT('%', ?, '%') AND p.equipment_id = ? AND p.plan_type = ? AND p.enabled = ?"));
        assertTrue(sql.contains("AND p.enabled = TRUE"));
        Map<Integer,Object> values=new HashMap<>();
        PreparedStatement jdbc=(PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().startsWith("set"))values.put((Integer)a[0],a[1]);return null;
        });
        new DefaultParameterHandler(statement,params,bound).setParameters(jdbc);
        assertEquals(Map.of(1,7L,2,"巡检",3,20L,4,"INSPECTION",5,false,6,LocalDate.of(2026,10,30)),values);
    }
}
