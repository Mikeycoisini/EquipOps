package com.workorder.mapper;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.common.response.BusinessExceptionHandler;
import com.equipops.domain.dto.workorder.WorkOrderPageQueryDto;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.enums.WorkOrderStatus;
import com.equipops.domain.enums.WorkOrderPriority;
import com.workorder.controller.WorkerderController;
import com.workorder.service.impl.WorkOrderServiceImpl;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实分页业务、MVC和MyBatis动态SQL检查，不连接数据库。 */
class WorkOrderOverduePageTest {
    private boolean expectOverdue;
    private int calls;
    private HttpStatus denied;
    private LocalDateTime before;
    private WorkOrderPageQueryDto query() {return new WorkOrderPageQueryDto(0,1000,"  pump  ",null,null,20L,10L);}
    private WorkOrderServiceImpl service() {
        before=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        var access=(CurrentAccessService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CurrentAccessService.class},(p,m,a)->{
            assertEquals("requireActiveTenantId",m.getName());
            if(denied!=null)throw new ResponseStatusException(denied);return 7L;
        });
        var mapper=(WorkOrderMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WorkOrderMapper.class},(p,m,a)->{
            assertEquals(expectOverdue?"selectOverdueWorkOrderPage":"selectWorkOrderPage",m.getName());
            calls++;var page=(Page<?>)a[0];assertEquals(1,page.getCurrent());assertEquals(100,page.getSize());
            assertEquals(7L,a[1]);assertEquals("pump",a[3]);
            if(expectOverdue) {
                var time=(LocalDateTime)a[4];assertFalse(time.isBefore(before));
                assertFalse(time.isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
            }
            var record=new WorkOrderEntity();record.setId(33L);record.setTenantId(7L);record.setTitle("维修水泵");
            var result=new Page<WorkOrderEntity>(1,100,1);result.setRecords(List.of(record));return result;
        });
        return new WorkOrderServiceImpl(mapper,null,null,null,access,null);
    }
    @Test void true走逾期路径并保留分页归属和VO转换() {
        expectOverdue=true;var query=query();query.setOverdueOnly(true);
        var result=service().getWorkOrderPage(query);
        assertEquals(1,result.getTotal());assertEquals(33L,result.getRecords().get(0).getId());
        assertTrue(result.getRecords().get(0).getTimeline().isEmpty());assertEquals(1,calls);
    }
    @Test void false及旧七参数调用保留原查询() {
        var query=query();assertNull(query.getOverdueOnly());var service=service();
        service.getWorkOrderPage(query);query.setOverdueOnly(false);service.getWorkOrderPage(query);
        assertEquals(2,calls);
    }
    @Test void 无效设备及负责人ID仍被拒绝() {
        var service=service();var query=query();query.setOverdueOnly(true);query.setEquipmentId(0L);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.getWorkOrderPage(query)).getStatusCode().value());
        query.setEquipmentId(20L);query.setAssigneeUserId(-1L);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.getWorkOrderPage(query)).getStatusCode().value());assertEquals(0,calls);
    }
    @Test void 身份失效时不能查询() {
        for(var status:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.CONFLICT)) {
            denied=status;assertEquals(status,assertThrows(ResponseStatusException.class,()->service().getWorkOrderPage(query())).getStatusCode());
        }
        assertEquals(0,calls);
    }
    @Test void GET绑定逾期开关且不接受前端企业和时间() throws Exception {
        expectOverdue=true;
        var mvc=MockMvcBuilders.standaloneSetup(new WorkerderController(service())).setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(get("/api/work-orders").param("overdueOnly","true").param("current","0").param("size","1000")
            .param("keyword","  pump  ").param("tenantId","999").param("asOf","2000-01-01T00:00:00"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].id").value(33));
    }
    @Test void 逾期SQL保留原筛选并与完成状态取交集且严格判断截止时间() throws Exception {
        var config=new Configuration();String resource="mapper/WorkOrderMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
        }
        var query=query();query.setOverdueOnly(true);query.setStatus(WorkOrderStatus.COMPLETED);query.setPriority(WorkOrderPriority.HIGH);
        var args=Map.of("tenantId",7L,"query",query,"keyword","pump","asOf",LocalDateTime.of(2026,10,31,12,0));
        var bound=config.getMappedStatement("com.workorder.mapper.WorkOrderMapper.selectOverdueWorkOrderPage").getBoundSql(args);
        String sql=bound.getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("WHERE tenant_id = ?"));assertTrue(sql.contains("AND status = ?"));
        assertTrue(sql.contains("AND priority = ?"));assertTrue(sql.contains("AND equipment_id = ?"));assertTrue(sql.contains("AND assignee_user_id = ?"));
        assertTrue(sql.contains("AND (order_no LIKE CONCAT('%', ?, '%') OR title LIKE CONCAT('%', ?, '%'))"));
        assertTrue(sql.contains("AND status IN ('PENDING', 'IN_PROGRESS', 'AWAITING_ACCEPTANCE') AND due_at < ?"));
        assertTrue(sql.endsWith("ORDER BY due_at ASC, id ASC"));
        assertEquals(List.of("tenantId","query.status","query.priority","query.equipmentId","query.assigneeUserId","keyword","keyword","asOf"),bound.getParameterMappings().stream().map(p->p.getProperty()).toList());
        String normal=config.getMappedStatement("com.workorder.mapper.WorkOrderMapper.selectWorkOrderPage").getBoundSql(args).getSql().replaceAll("\\s+"," ").trim();
        assertFalse(normal.contains("due_at <"));assertTrue(normal.endsWith("ORDER BY created_at DESC, id DESC"));
    }
}
