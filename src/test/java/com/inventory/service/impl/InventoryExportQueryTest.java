package com.inventory.service.impl;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.inventory.InventoryExportPageQueryDto;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.enums.InventoryExportStatus;
import com.equipops.domain.vo.InventoryExportTaskVO;
import com.inventory.mapper.InventoryExportTaskMapper;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class InventoryExportQueryTest {
    InventoryExportServiceImpl service; InventoryExportTaskVO task;
    boolean allowed=true; int accessCalls,mapperCalls;
    @BeforeEach void setup() {
        service=new InventoryExportServiceImpl();task=new InventoryExportTaskVO();task.setId(9L);task.setStatus(InventoryExportStatus.SUCCEEDED);
        var tenant=new TenantEntity();tenant.setId(7L);
        var access=proxy(CurrentAccessService.class,(p,m,a)->{
            assertEquals("requireTenantAccess",m.getName());accessCalls++;
            if(!allowed)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"成员已失效");
            return new TenantAccess(4L,tenant,3L,Set.of("TENANT_ADMIN"));
        });
        var mapper=proxy(InventoryExportTaskMapper.class,(p,m,a)->{
            mapperCalls++;
            if(m.getName().equals("selectOwnTask")){assertArrayEquals(new Object[]{7L,4L,9L},a);return task;}
            assertEquals("selectOwnTaskPage",m.getName());assertEquals(7L,a[1]);assertEquals(4L,a[2]);
            @SuppressWarnings("unchecked") var page=(Page<InventoryExportTaskVO>)a[0];
            var query=(InventoryExportPageQueryDto)a[3];assertEquals(InventoryExportStatus.SUCCEEDED,query.getStatus());
            page.setTotal(1);page.setRecords(List.of(task));return page;
        });
        ReflectionTestUtils.setField(service,"currentAccessService",access);ReflectionTestUtils.setField(service,"taskMapper",mapper);
    }
    <T>T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type},handler));}
    InventoryExportPageQueryDto query(long current,long size){return new InventoryExportPageQueryDto(current,size,InventoryExportStatus.SUCCEEDED);}
    IPage<InventoryExportTaskVO> page(InventoryExportPageQueryDto q){return ReflectionTestUtils.invokeMethod(service,"getTaskPage",q);}
    InventoryExportTaskVO detail(Long id){return ReflectionTestUtils.invokeMethod(service,"getTaskDetail",id);}
    @Test void 列表采用可信身份并保留状态筛选和分页结果(){var result=page(query(2,10));assertEquals(2,result.getCurrent());assertEquals(10,result.getSize());assertEquals(1,result.getTotal());assertEquals(9L,result.getRecords().get(0).getId());assertEquals(1,accessCalls);}
    @Test void 非正页码与页大小按约定收敛(){var result=page(query(-3,0));assertEquals(1,result.getCurrent());assertEquals(1,result.getSize());}
    @Test void 单页不能超过一百条(){assertEquals(100,page(query(1,10000)).getSize());}
    @Test void 过大页码不能溢出后查询第一页(){assertEquals(400,assertThrows(ResponseStatusException.class,()->page(query(Long.MAX_VALUE,100))).getStatusCode().value());assertEquals(0,mapperCalls);}
    @Test void 空查询参数返回400(){assertEquals(400,assertThrows(ResponseStatusException.class,()->page(null)).getStatusCode().value());}
    @Test void 详情限定本人且管理员也不扩大查询范围(){assertEquals(9L,detail(9L).getId());assertEquals(1,accessCalls);}
    @Test void 不存在或不可见详情统一404(){task=null;assertEquals(404,assertThrows(ResponseStatusException.class,()->detail(9L)).getStatusCode().value());}
    @Test void 非法任务ID返回400(){for(Long id:Arrays.asList(null,0L,-1L))assertEquals(400,assertThrows(ResponseStatusException.class,()->detail(id)).getStatusCode().value());assertEquals(0,mapperCalls);}
    @Test void 失效成员不能查询列表或详情(){allowed=false;assertEquals(403,assertThrows(ResponseStatusException.class,()->page(query(1,10))).getStatusCode().value());assertEquals(403,assertThrows(ResponseStatusException.class,()->detail(9L)).getStatusCode().value());assertEquals(0,mapperCalls);}
}
