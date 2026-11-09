package com.statistics;

import com.auth.service.CurrentAccessService;
import com.common.response.BusinessExceptionHandler;
import com.equipops.domain.dto.statistics.EquipmentProcessingDurationQueryDto;
import com.equipops.domain.vo.EquipmentProcessingDurationVO;
import com.statistics.controller.EquipmentStatisticsController;
import com.statistics.mapper.EquipmentStatisticsMapper;
import com.statistics.service.impl.EquipmentStatisticsServiceImpl;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.sql.PreparedStatement;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实业务和MVC及MyBatis参数绑定；不执行真实数据库查询。 */
class EquipmentProcessingDurationTest {
    private HttpStatus denied;
    private boolean empty;
    private int reads;
    private Object[] arguments;
    private EquipmentStatisticsServiceImpl service() {
        var access=(CurrentAccessService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CurrentAccessService.class},(p,m,a)->{
            assertEquals("requireActiveTenantId",m.getName());
            if(denied!=null)throw new ResponseStatusException(denied);
            return 7L;
        });
        var mapper=(EquipmentStatisticsMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{EquipmentStatisticsMapper.class},(p,m,a)->{
            assertEquals("selectProcessingDuration",m.getName());assertEquals(7L,a[0]);reads++;arguments=a;
            return empty?List.of():List.of(new EquipmentProcessingDurationVO(20L,"EQ-20","空压机",2L,new BigDecimal("7200.25")));
        });
        return new EquipmentStatisticsServiceImpl(access,mapper);
    }
    @Test void 默认自然日范围及秒数和样本数正确返回() {
        var before=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var result=service().getProcessingDuration(new EquipmentProcessingDurationQueryDto());
        var after=LocalDate.now(ZoneId.of("Asia/Shanghai"));var end=(LocalDateTime)arguments[2];
        assertTrue(end.equals(before.plusDays(1).atStartOfDay())||end.equals(after.plusDays(1).atStartOfDay()));
        assertEquals(end.minusDays(30),arguments[1]);assertEquals(10,arguments[3]);
        assertEquals(2L,result.get(0).getCompletedOrderCount());assertEquals(new BigDecimal("7200.25"),result.get(0).getAvgProcessingSeconds());
    }
    @Test void 支持一天及最大365天100条() {
        var service=service();
        for(int days:new int[]{1,365}) {
            service.getProcessingDuration(new EquipmentProcessingDurationQueryDto(days,100));
            assertEquals(((LocalDateTime)arguments[2]).minusDays(days),arguments[1]);assertEquals(100,arguments[3]);
        }
    }
    @Test void 无效或空参数不执行查询() {
        var service=service();
        for(var query:new EquipmentProcessingDurationQueryDto[]{null,new EquipmentProcessingDurationQueryDto(null,10),new EquipmentProcessingDurationQueryDto(0,10),new EquipmentProcessingDurationQueryDto(366,10),new EquipmentProcessingDurationQueryDto(30,null),new EquipmentProcessingDurationQueryDto(30,0),new EquipmentProcessingDurationQueryDto(30,101)}) {
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.getProcessingDuration(query)).getStatusCode().value());
        }
        assertEquals(0,reads);
    }
    @Test void 未登录失效成员及未选企业被拒绝() {
        for(var status:new HttpStatus[]{HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.CONFLICT}) {
            denied=status;
            assertEquals(status,assertThrows(ResponseStatusException.class,()->service().getProcessingDuration(new EquipmentProcessingDurationQueryDto())).getStatusCode());
        }
        assertEquals(0,reads);
    }
    @Test void 无有效样本返回空列表() {empty=true;assertTrue(service().getProcessingDuration(new EquipmentProcessingDurationQueryDto()).isEmpty());}
    @Test void GET绑定参数并返回秒数且忽略客户端企业ID() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new EquipmentStatisticsController(service())).setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(get("/api/statistics/equipment/processing-duration").param("days","7").param("limit","3").param("tenantId","999"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].avgProcessingSeconds").value(7200.25))
            .andExpect(jsonPath("$.data[0].completedOrderCount").value(2));
        assertEquals(7L,arguments[0]);assertEquals(3,arguments[3]);assertEquals(((LocalDateTime)arguments[2]).minusDays(7),arguments[1]);
    }
    @Test void GET非法参数返回统一400() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new EquipmentStatisticsController(service())).setControllerAdvice(new BusinessExceptionHandler()).build();
        for(String value:new String[]{"0","366","abc","1.5",""}) {
            mvc.perform(get("/api/statistics/equipment/processing-duration").param("days",value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        assertEquals(0,reads);
    }
    @Test void SQL按完成日筛选有效时间限定企业且参数安全绑定() throws Exception {
        var config=new Configuration();String resource="mapper/EquipmentStatisticsMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
        }
        var method=EquipmentStatisticsMapper.class.getMethod("selectProcessingDuration",Long.class,LocalDateTime.class,LocalDateTime.class,int.class);
        var start=LocalDateTime.of(2026,10,1,0,0);var end=start.plusDays(30);
        var args=new ParamNameResolver(config,method).getNamedParams(new Object[]{7L,start,end,10});
        var statement=config.getMappedStatement("com.statistics.mapper.EquipmentStatisticsMapper.selectProcessingDuration");var bound=statement.getBoundSql(args);
        String sql=bound.getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("e.tenant_id = w.tenant_id AND e.id = w.equipment_id"));assertTrue(sql.contains("WHERE w.tenant_id = ?"));
        assertTrue(sql.contains("w.status = 'COMPLETED'"));assertTrue(sql.contains("w.created_at IS NOT NULL AND w.completed_at IS NOT NULL"));
        assertTrue(sql.contains("w.completed_at >= ? AND w.completed_at < ?"));assertTrue(sql.contains("w.completed_at >= w.created_at"));
        assertFalse(sql.contains("w.created_at >= ?"));assertFalse(sql.contains("work_order_logs"));
        assertTrue(sql.contains("ROUND(AVG(TIMESTAMPDIFF(MICROSECOND, w.created_at, w.completed_at)) / 1000000, 2)"));
        assertTrue(sql.endsWith("ORDER BY avg_processing_seconds DESC, e.id ASC LIMIT ?"));
        assertEquals(BigDecimal.class,statement.getResultMaps().get(0).getResultMappings().stream().filter(m->m.getProperty().equals("avgProcessingSeconds")).findFirst().orElseThrow().getJavaType());
        Map<Integer,Object> values=new HashMap<>();
        var jdbc=(PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{if(m.getName().startsWith("set"))values.put((Integer)a[0],a[1]);return null;});
        new DefaultParameterHandler(statement,args,bound).setParameters(jdbc);
        assertEquals(Map.of(1,7L,2,start,3,end,4,10),values);
    }
}
