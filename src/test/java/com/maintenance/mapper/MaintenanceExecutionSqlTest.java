package com.maintenance.mapper;

import com.equipops.domain.entity.MaintenanceExecutionEntity;
import com.equipops.domain.enums.MaintenancePlanType;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 执行记录SQL映射和JDBC参数契约，不连接真实数据库。 */
class MaintenanceExecutionSqlTest {
    private Configuration config() throws Exception {
        var config=new Configuration(); String resource="mapper/MaintenancePlanMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input); new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
        }
        return config;
    }
    @Test void 请求和周期查询均限定本企业() throws Exception {
        var config=config();
        var request=config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.findExecutionByRequest").getBoundSql(Map.of("tenantId",7L,"requestId","exec-1"));
        assertTrue(request.getSql().replaceAll("\\s+"," ").contains("WHERE tenant_id = ? AND request_id = ?"));
        var period=config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.countExecutionForPeriod").getBoundSql(Map.of("tenantId",7L,"planId",100L,"scheduledDate",LocalDate.of(2026,10,30)));
        assertTrue(period.getSql().replaceAll("\\s+"," ").contains("WHERE tenant_id = ? AND plan_id = ? AND scheduled_date = ?"));
    }
    @Test void 推进日期不覆盖其他计划字段且有原日期和启用状态条件() throws Exception {
        var bound=config().getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.advancePlanDueDate")
                .getBoundSql(Map.of("tenantId",7L,"planId",100L,"expectedDueDate",LocalDate.of(2026,10,30),"nextDueDate",LocalDate.of(2026,11,6)));
        assertEquals("UPDATE maintenance_plans SET next_due_date = ? WHERE tenant_id = ? AND id = ? AND enabled = TRUE AND next_due_date = ?",bound.getSql().replaceAll("\\s+"," ").trim());
        assertEquals(List.of("nextDueDate","tenantId","planId","expectedDueDate"),bound.getParameterMappings().stream().map(p->p.getProperty()).toList());
    }
    @Test void SQL读取可信历史快照且Java只绑定运行参数() throws Exception {
        var config=config(); var statement=config.getMappedStatement("com.maintenance.mapper.MaintenancePlanMapper.insertExecution");
        var value=new MaintenanceExecutionEntity(); value.setTenantId(7L); value.setPlanId(100L);
        value.setRequestId("exec-1"); value.setScheduledDate(LocalDate.of(2026,10,30));
        value.setExecutedAt(LocalDateTime.of(2026,11,2,12,0)); value.setExecutedBy(30L);
        value.setResult("已完成"); value.setNextDueDate(LocalDate.of(2026,11,9));
        // 即使Java对象带有伪造快照，INSERT SELECT也必须从已校验计划及关联记录取值。
        value.setSiteId(999L); value.setEquipmentId(999L); value.setPlanName("伪造名称");
        value.setCycleDays(999); value.setEquipmentName("伪造设备"); value.setExecutedByName("伪造人员");
        var method=MaintenancePlanMapper.class.getMethod("insertExecution",MaintenanceExecutionEntity.class);
        Object params=new ParamNameResolver(config,method).getNamedParams(new Object[]{value});
        var bound=statement.getBoundSql(params); String sql=bound.getSql().replaceAll("\\s+"," ").trim();
        assertArrayEquals(new String[]{"execution.id"},statement.getKeyProperties());
        assertTrue(sql.contains("SELECT p.tenant_id, p.id, p.site_id, p.equipment_id, ?, p.name, p.plan_type, p.content, p.cycle_days, p.next_due_date, ?, ?, ?, ?, e.asset_code, e.name, u.display_name"));
        assertTrue(sql.contains("e.tenant_id = p.tenant_id AND e.id = p.equipment_id"));
        assertTrue(sql.contains("tm.tenant_id = p.tenant_id AND tm.user_id = ?"));
        assertTrue(sql.contains("WHERE p.tenant_id = ? AND p.id = ?"));
        assertTrue(sql.contains("p.enabled = TRUE AND p.assignee_user_id = tm.user_id"));
        assertTrue(sql.contains("p.next_due_date = ?"));
        assertTrue(sql.contains("tm.enabled = TRUE AND u.enabled = TRUE"));
        assertEquals(List.of("execution.requestId","execution.executedAt","execution.executedBy",
                "execution.result","execution.nextDueDate","execution.executedBy","execution.tenantId",
                "execution.planId","execution.scheduledDate"),bound.getParameterMappings().stream().map(p->p.getProperty()).toList());
        Map<Integer,Object> values=new HashMap<>();
        PreparedStatement jdbc=(PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().startsWith("set"))values.put((Integer)a[0],a[1]); return null;
        });
        new DefaultParameterHandler(statement,params,bound).setParameters(jdbc);
        assertEquals(Map.of(1,"exec-1",2,LocalDateTime.of(2026,11,2,12,0),3,30L,4,"已完成",
                5,LocalDate.of(2026,11,9),6,30L,7,7L,8,100L,9,LocalDate.of(2026,10,30)),values);
    }

}
