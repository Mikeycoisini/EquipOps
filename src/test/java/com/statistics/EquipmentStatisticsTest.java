package com.statistics;

import com.auth.service.CurrentAccessService;
import com.common.response.BusinessExceptionHandler;
import com.equipops.domain.dto.statistics.EquipmentRepairCountQueryDto;
import com.equipops.domain.vo.EquipmentRepairCountVO;
import com.statistics.controller.EquipmentStatisticsController;
import com.statistics.mapper.EquipmentStatisticsMapper;
import com.statistics.service.impl.EquipmentStatisticsServiceImpl;
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

/** 非数据库测试，验证真实业务与MVC，身份和数据访问采用边界替身。 */
class EquipmentStatisticsTest {
    private HttpStatus denied;
    private boolean empty;
    private int reads;
    private Object[] arguments;
    private EquipmentStatisticsServiceImpl service() {
        CurrentAccessService access = (CurrentAccessService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CurrentAccessService.class}, (p,m,a) -> {
            assertEquals("requireActiveTenantId", m.getName());
            if (denied != null) throw new ResponseStatusException(denied);
            return 7L;
        });
        EquipmentStatisticsMapper mapper = (EquipmentStatisticsMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{EquipmentStatisticsMapper.class}, (p,m,a) -> {
            assertEquals("selectRepairCounts", m.getName()); reads++; arguments = a;
            assertEquals(7L, a[0]);
            return empty ? List.of() : List.of(new EquipmentRepairCountVO(20L,"EQ-20","空压机",5L));
        });
        return new EquipmentStatisticsServiceImpl(access, mapper);
    }
    @Test void 普通成员默认统计近30个上海自然日并返回次数() {
        LocalDate before = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var result = service().getRepairCounts(new EquipmentRepairCountQueryDto());
        LocalDate after = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var start = (LocalDateTime) arguments[1];
        var end = (LocalDateTime) arguments[2];
        assertTrue(end.equals(before.plusDays(1).atStartOfDay()) || end.equals(after.plusDays(1).atStartOfDay()));
        assertEquals(end.minusDays(30), start); assertEquals(10, arguments[3]);
        assertEquals(5L,result.get(0).getWorkOrderCount()); assertEquals(20L,result.get(0).getEquipmentId());
    }
    @Test void 一天及最大范围参数均正确传递() {
        var service = service();
        for(int days : new int[]{1,365}) {
            service.getRepairCounts(new EquipmentRepairCountQueryDto(days,100));
            assertEquals(((LocalDateTime)arguments[2]).minusDays(days), arguments[1]); assertEquals(100,arguments[3]);
        }
    }
    @Test void 非法或空参数不查数据库() {
        var service = service();
        for(var query : new EquipmentRepairCountQueryDto[]{null,new EquipmentRepairCountQueryDto(null,10),new EquipmentRepairCountQueryDto(0,10),new EquipmentRepairCountQueryDto(366,10),new EquipmentRepairCountQueryDto(30,null),new EquipmentRepairCountQueryDto(30,0),new EquipmentRepairCountQueryDto(30,101)}) {
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.getRepairCounts(query)).getStatusCode().value());
        }
        assertEquals(0,reads);
    }
    @Test void 未登录失效成员及未选择企业禁止查询() {
        for(var status : new HttpStatus[]{HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.CONFLICT}) {
            denied=status;
            assertEquals(status,assertThrows(ResponseStatusException.class,()->service().getRepairCounts(new EquipmentRepairCountQueryDto())).getStatusCode());
        }
        assertEquals(0,reads);
    }
    @Test void 无工单返回空列表() {empty=true;assertTrue(service().getRepairCounts(new EquipmentRepairCountQueryDto()).isEmpty());}
    @Test void GET绑定参数且不会使用客户端企业ID() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new EquipmentStatisticsController(service())).setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(get("/api/statistics/equipment/repair-counts").param("days","7").param("limit","3").param("tenantId","999"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].assetCode").value("EQ-20"));
        assertEquals(3,arguments[3]);assertEquals(((LocalDateTime)arguments[2]).minusDays(7),arguments[1]);
    }
    @Test void GET非法参数返回统一400而非执行查询() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new EquipmentStatisticsController(service())).setControllerAdvice(new BusinessExceptionHandler()).build();
        for(String value : new String[]{"0","366","abc","1.5",""}) {
            mvc.perform(get("/api/statistics/equipment/repair-counts").param("days",value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        assertEquals(0,reads);
    }
}
