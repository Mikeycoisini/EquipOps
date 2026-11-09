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

/** 接口契约和真实MyBatis映射检查，不加载数据库或安全链。 */
class WorkOrderStatisticsContractTest {
    @Test void GET返回趋势且非法查询参数返回400() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new WorkOrderStatisticsController(new com.statistics.service.WorkOrderStatisticsService() {
            @Override public List<com.equipops.domain.vo.WorkOrderDailyTrendVO> getDailyTrend(com.equipops.domain.dto.statistics.WorkOrderDailyTrendQueryDto query) {
                java.time.LocalDate today=java.time.LocalDate.of(2026,10,31);
                return List.of(new com.equipops.domain.vo.WorkOrderDailyTrendVO(today,0L,2L));
            }
            @Override public com.equipops.domain.vo.WorkOrderOverdueSummaryVO getOverdueSummary() {
                throw new AssertionError("趋势接口不能调用逾期汇总");
            }
            }))
            .setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(get("/api/statistics/work-orders/daily-trend"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].completedCount").value(2));
        mvc.perform(get("/api/statistics/work-orders/daily-trend").param("days","0"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }
    @Test void XML独立筛选创建与完成时间且两个分支都限定企业() throws Exception {
        var config=new Configuration();String resource="mapper/WorkOrderStatisticsMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
        }
        var method=WorkOrderStatisticsMapper.class.getMethod("selectDailyTrend",Long.class,LocalDateTime.class,LocalDateTime.class);
        var args=new ParamNameResolver(config,method).getNamedParams(new Object[]{7L,LocalDateTime.of(2026,10,1,0,0),LocalDateTime.of(2026,11,1,0,0)});
        var bound=config.getMappedStatement("com.statistics.mapper.WorkOrderStatisticsMapper.selectDailyTrend").getBoundSql(args);
        String sql=bound.getSql().replaceAll("\\s+"," ").trim();
        String[] branches=sql.split("UNION ALL");assertEquals(2,branches.length);
        assertTrue(branches[0].contains("WHERE tenant_id = ? AND created_at >= ? AND created_at < ?"));
        assertTrue(branches[1].contains("WHERE tenant_id = ? AND completed_at >= ? AND completed_at < ?"));
        assertFalse(branches[1].contains("created_at"));
        assertTrue(sql.endsWith("GROUP BY stat_date ORDER BY stat_date ASC"));
        assertEquals(List.of("tenantId","startTime","endTime","tenantId","startTime","endTime"),bound.getParameterMappings().stream().map(p->p.getProperty()).toList());
    }
}
