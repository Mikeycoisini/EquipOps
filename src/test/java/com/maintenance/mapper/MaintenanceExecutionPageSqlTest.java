package com.maintenance.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaintenanceExecutionPageSqlTest {
    @Test void 分页SQL同时限定企业和计划且只读历史快照() throws Exception {
        var config=new Configuration();String resource="mapper/MaintenancePlanMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
        }
        var method=MaintenancePlanMapper.class.getMethod("selectExecutionPage",Page.class,Long.class,Long.class);
        Object params=new ParamNameResolver(config,method).getNamedParams(new Object[]{new Page<>(2,5),7L,100L});
        var statement=config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.selectExecutionPage");
        var bound=statement.getBoundSql(params);String sql=bound.getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("FROM maintenance_executions WHERE tenant_id = ? AND plan_id = ?"));
        assertTrue(sql.endsWith("ORDER BY executed_at DESC, id DESC"));assertFalse(sql.contains("JOIN"));
        assertTrue(sql.contains("plan_name"));assertTrue(sql.contains("equipment_name"));assertTrue(sql.contains("executed_by_name"));
        assertEquals(MaintenanceExecutionVO.class,statement.getResultMaps().get(0).getType());
        assertEquals(List.of("tenantId","planId"),bound.getParameterMappings().stream().map(p->p.getProperty()).toList());
        Map<Integer,Object> values=new HashMap<>();
        PreparedStatement jdbc=(PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().startsWith("set"))values.put((Integer)a[0],a[1]);return null;
        });
        new DefaultParameterHandler(statement,params,bound).setParameters(jdbc);
        assertEquals(Map.of(1,7L,2,100L),values);
    }
}
