package com.statistics;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import com.statistics.mapper.EquipmentStatisticsMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentStatisticsSqlTest {
    @Test void 统计SQL隔离企业使用半开区间且不重复关联日志计数() throws Exception {
        var config=new Configuration(); String resource="mapper/EquipmentStatisticsMapper.xml";
        try(var stream=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);new XMLMapperBuilder(stream,config,resource,config.getSqlFragments()).parse();
        }
        var method=EquipmentStatisticsMapper.class.getMethod("selectRepairCounts",Long.class,LocalDateTime.class,LocalDateTime.class,int.class);
        LocalDateTime start=LocalDateTime.of(2026,10,1,0,0),end=LocalDateTime.of(2026,10,31,0,0);
        var args=new ParamNameResolver(config,method).getNamedParams(new Object[]{7L,start,end,10});
        var statement=config.getMappedStatement("com.statistics.mapper.EquipmentStatisticsMapper.selectRepairCounts");
        var bound=statement.getBoundSql(args);String sql=bound.getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("e.tenant_id = w.tenant_id AND e.id = w.equipment_id"));
        assertTrue(sql.contains("WHERE w.tenant_id = ? AND w.source = 'REPAIR_REPORT' AND w.created_at >= ? AND w.created_at < ?"));
        assertTrue(sql.contains("COUNT(*) AS work_order_count"));assertFalse(sql.contains("work_order_logs"));assertFalse(sql.contains("w.status"));
        assertTrue(sql.endsWith("GROUP BY e.id, e.asset_code, e.name ORDER BY work_order_count DESC, e.id ASC LIMIT ?"));
        Map<Integer,Object> values=new HashMap<>();
        var jdbc=(PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{if(m.getName().startsWith("set"))values.put((Integer)a[0],a[1]);return null;});
        new DefaultParameterHandler(statement,args,bound).setParameters(jdbc);
        assertEquals(Map.of(1,7L,2,start,3,end,4,10),values);
    }
}
