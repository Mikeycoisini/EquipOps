package com.tenant.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.tenant.SiteUpsertDto;
import com.equipops.domain.entity.SiteEntity;
import com.tenant.mapper.SiteMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

/** 厂区创建必须真正持久化；直接调用服务同样要求管理员并拒绝半空参数。 */
class SiteServiceScopeTest {
    private int writes;
    private boolean denyAdmin;
    private SiteServiceImpl service() {
        var service = new SiteServiceImpl();
        var access = (CurrentAccessService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CurrentAccessService.class}, (p,m,a) -> {
            if (m.getName().equals("requireTenantAdminTenantId") && denyAdmin)
                throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "需要管理员");
            return 7L;
        });
        var mapper = (SiteMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SiteMapper.class}, (p,m,a) -> {
            if (m.getName().equals("selectCount")) return 0L;
            if (m.getName().equals("insert")) { writes++; ((SiteEntity)a[0]).setId(100L); return 1; }
            if (m.getName().equals("selectOne")) return SiteEntity.builder().id(100L).tenantId(7L).siteCode("S1").name("厂区一").enabled(true).isDefault(false).build();
            throw new AssertionError(m.getName());
        });
        ReflectionTestUtils.setField(service,"currentAccessService",access);
        ReflectionTestUtils.setField(service,"siteMapper",mapper);
        ReflectionTestUtils.setField(service,"auditEventService",org.mockito.Mockito.mock(com.audit.service.AuditEventService.class));
        return service;
    }
    @Test void 创建必须写入并返回数据库主键() {
        var result = service().createSite(new SiteUpsertDto(" S1 ", " 厂区一 ", null, true));
        assertEquals(1, writes); assertEquals(100L,result.getId());
    }
    @Test void 普通成员不能创建厂区() {
        denyAdmin=true;
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service().createSite(new SiteUpsertDto("S1","厂区一",null,true))).getStatusCode().value());
        assertEquals(0,writes);
    }
    @Test void 编号或名称任一空白均拒绝() {
        for (var dto : new SiteUpsertDto[]{new SiteUpsertDto(" ","名称",null,true),new SiteUpsertDto("S1"," ",null,true),new SiteUpsertDto(null,"名称",null,true)}) {
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service().createSite(dto)).getStatusCode().value());
        }
        assertEquals(0,writes);
    }
    @Test void 普通成员不能修改厂区() {
        denyAdmin=true;
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service().updateSite(100L,new SiteUpsertDto("S1","名称",null,true))).getStatusCode().value());
    }
}
