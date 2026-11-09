package com.statistics;

import com.common.response.BusinessExceptionHandler;
import com.statistics.controller.WorkOrderStatisticsController;
import com.statistics.mapper.WorkOrderStatisticsMapper;
import com.statistics.service.impl.WorkOrderStatisticsServiceImpl;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 汇总响应、权限及真实MyBatis映射检查，不加载数据库和认证链。 */
class WorkOrderOverdueSkeletonTest {
    @Test void 当前企业成员得到汇总及服务端上海统计时间() throws Exception {
        var before=LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        var access=(com.auth.service.CurrentAccessService)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{com.auth.service.CurrentAccessService.class},(p,m,a)->{
            assertEquals("requireActiveTenantId",m.getName());return 7L;
        });
        var mapper=(WorkOrderStatisticsMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WorkOrderStatisticsMapper.class},(p,m,a)->{
            assertEquals("selectOverdueSummary",m.getName());assertEquals(7L,a[0]);
            var time=(LocalDateTime)a[1];
            assertFalse(time.isBefore(before));assertFalse(time.isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))));
            return new com.equipops.domain.vo.WorkOrderOverdueSummaryVO(time,6L,1L,2L,3L,4L);
        });
        var mvc=MockMvcBuilders.standaloneSetup(new WorkOrderStatisticsController(new WorkOrderStatisticsServiceImpl(access,mapper)))
            .setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(get("/api/statistics/work-orders/overdue-summary").param("tenantId","999").param("asOf","2000-01-01T00:00:00"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.asOf").isString())
            .andExpect(jsonPath("$.data.overdueCount").value(6)).andExpect(jsonPath("$.data.pendingOverdueCount").value(1))
            .andExpect(jsonPath("$.data.inProgressOverdueCount").value(2)).andExpect(jsonPath("$.data.awaitingAcceptanceOverdueCount").value(3))
            .andExpect(jsonPath("$.data.withoutDeadlineCount").value(4));
    }
    @Test void 无效身份在查询前被拒绝() {
        for(var status:List.of(org.springframework.http.HttpStatus.UNAUTHORIZED,org.springframework.http.HttpStatus.FORBIDDEN,org.springframework.http.HttpStatus.CONFLICT)) {
            var access=(com.auth.service.CurrentAccessService)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{com.auth.service.CurrentAccessService.class},(p,m,a)->{
                assertEquals("requireActiveTenantId",m.getName());throw new org.springframework.web.server.ResponseStatusException(status);
            });
            var mapper=(WorkOrderStatisticsMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WorkOrderStatisticsMapper.class},(p,m,a)->{throw new AssertionError("不能执行数据查询");});
            var service=new WorkOrderStatisticsServiceImpl(access,mapper);
            assertEquals(status,assertThrows(org.springframework.web.server.ResponseStatusException.class,service::getOverdueSummary).getStatusCode());
        }
    }
    @Test void SQL限定企业未完成状态并使用严格截止边界和空值计数() throws Exception {
        var config=new Configuration();String resource="mapper/WorkOrderStatisticsMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
        }
        var method=WorkOrderStatisticsMapper.class.getMethod("selectOverdueSummary",Long.class,LocalDateTime.class);
        var args=new ParamNameResolver(config,method).getNamedParams(new Object[]{7L,LocalDateTime.of(2026,10,31,12,0)});
        var statement=config.getMappedStatement("com.statistics.mapper.WorkOrderStatisticsMapper.selectOverdueSummary");
        var bound=statement.getBoundSql(args);String sql=bound.getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("WHERE tenant_id = ? AND status IN ('PENDING', 'IN_PROGRESS', 'AWAITING_ACCEPTANCE')"));
        assertTrue(sql.contains("SELECT ? AS as_of"));assertFalse(sql.contains("due_at <="));
        assertEquals(4,sql.split(java.util.regex.Pattern.quote("due_at < ?"),-1).length-1);
        assertEquals(5,sql.split("COALESCE",-1).length-1);
        assertTrue(sql.contains("CASE WHEN due_at IS NULL THEN 1 ELSE 0 END"));
        assertFalse(sql.contains("created_at"));assertFalse(sql.contains("GROUP BY"));
        assertEquals(List.of("asOf","asOf","asOf","asOf","asOf","tenantId"),bound.getParameterMappings().stream().map(p->p.getProperty()).toList());
        assertEquals(6,statement.getResultMaps().get(0).getResultMappings().size());
    }
}
