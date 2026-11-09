package com.statistics;

import com.auth.service.CurrentAccessService;
import com.common.response.BusinessExceptionHandler;
import com.equipops.domain.dto.statistics.WorkOrderDailyTrendQueryDto;
import com.equipops.domain.vo.WorkOrderDailyTrendVO;
import com.statistics.controller.WorkOrderStatisticsController;
import com.statistics.mapper.WorkOrderStatisticsMapper;
import com.statistics.service.impl.WorkOrderStatisticsServiceImpl;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实业务及独立MVC；不连接数据库或加载认证过滤链。 */
class WorkOrderDailyTrendServiceTest {
    private HttpStatus denied;
    private boolean sparse;
    private int reads;
    private Object[] arguments;
    private WorkOrderStatisticsServiceImpl service() {
        var access=(CurrentAccessService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CurrentAccessService.class},(p,m,a)->{
            assertEquals("requireActiveTenantId",m.getName());
            if(denied!=null)throw new ResponseStatusException(denied);
            return 7L;
        });
        var mapper=(WorkOrderStatisticsMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WorkOrderStatisticsMapper.class},(p,m,a)->{
            assertEquals("selectDailyTrend",m.getName());assertEquals(7L,a[0]);reads++;arguments=a;
            LocalDate start=((LocalDateTime)a[1]).toLocalDate();
            LocalDate last=((LocalDateTime)a[2]).toLocalDate().minusDays(1);
            // 模拟有完成但无新增的日期，以及完全缺失的中间日期；有意倒序返回。
            return sparse ? List.of(new WorkOrderDailyTrendVO(last,0L,2L),new WorkOrderDailyTrendVO(start,5L,0L)) : List.of();
        });
        return new WorkOrderStatisticsServiceImpl(access,mapper);
    }
    @Test void 默认30天全部无数据仍返回连续零值日期() {
        LocalDate before=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var result=service().getDailyTrend(new WorkOrderDailyTrendQueryDto());
        LocalDate after=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate last=result.get(29).getDate();
        assertTrue(last.equals(before)||last.equals(after));assertEquals(30,result.size());
        assertEquals(last.minusDays(29).atStartOfDay(),arguments[1]);assertEquals(last.plusDays(1).atStartOfDay(),arguments[2]);
        for(int i=0;i<30;i++) {
            assertEquals(last.minusDays(29-i),result.get(i).getDate());
            assertEquals(0L,result.get(i).getCreatedCount());assertEquals(0L,result.get(i).getCompletedCount());
        }
    }
    @Test void 稀疏日期补零且保留只有完成数量的日期并升序返回() {
        sparse=true;var result=service().getDailyTrend(new WorkOrderDailyTrendQueryDto(3));
        assertEquals(3,result.size());assertEquals(5L,result.get(0).getCreatedCount());
        assertEquals(0L,result.get(1).getCreatedCount());assertEquals(0L,result.get(1).getCompletedCount());
        assertEquals(0L,result.get(2).getCreatedCount());assertEquals(2L,result.get(2).getCompletedCount());
        assertEquals(result.get(0).getDate().plusDays(2),result.get(2).getDate());
    }
    @Test void 一天和365天均包含今天且查询一次() {
        var service=service();
        for(int days:new int[]{1,365}) {
            var result=service.getDailyTrend(new WorkOrderDailyTrendQueryDto(days));
            assertEquals(days,result.size());
            assertEquals(((LocalDateTime)arguments[2]).minusDays(days),arguments[1]);
        }
        assertEquals(2,reads);
    }
    @Test void 无效输入和无效身份不执行数据查询() {
        var service=service();
        for(var query:new WorkOrderDailyTrendQueryDto[]{null,new WorkOrderDailyTrendQueryDto(null),new WorkOrderDailyTrendQueryDto(0),new WorkOrderDailyTrendQueryDto(366)}) {
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.getDailyTrend(query)).getStatusCode().value());
        }
        for(var status:new HttpStatus[]{HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.CONFLICT}) {
            denied=status;
            assertEquals(status,assertThrows(ResponseStatusException.class,()->service.getDailyTrend(new WorkOrderDailyTrendQueryDto())).getStatusCode());
        }
        assertEquals(0,reads);
    }
    @Test void GET返回连续日期且客户端企业参数不改变查询范围() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new WorkOrderStatisticsController(service())).setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(get("/api/statistics/work-orders/daily-trend").param("days","1").param("tenantId","999"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].createdCount").value(0)).andExpect(jsonPath("$.data[0].completedCount").value(0))
            .andExpect(jsonPath("$.data[0].date").isString());
        assertEquals(7L,arguments[0]);
    }
}
