package com.support;

import com.audit.service.AuditEventService;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.dto.tenant.SiteUpsertDto;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.enums.*;
import com.tenant.service.SiteService;
import com.tenant.service.impl.AuditedSiteService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 厂区业务事务退出后独立记录拒绝与失败，始终保留原始错误且不保存正文。 */
class SiteAuditFacadeTest {
    @Test void 普通成员创建被拒绝记录可信企业审计() {
        var delegate=mock(SiteService.class);var audit=mock(AuditEventService.class);var access=mock(CurrentAccessService.class);
        var tenant=new TenantEntity();tenant.setId(7L);
        when(access.requireTenantAccess()).thenReturn(new TenantAccess(19L,tenant,21L,Set.of()));
        var request=new SiteUpsertDto("private-code","private-name","private-address",true);
        var failure=new ResponseStatusException(HttpStatus.FORBIDDEN,"private-error");
        when(delegate.createSite(request)).thenThrow(failure);
        assertSame(failure,assertThrows(ResponseStatusException.class,()->new AuditedSiteService(delegate,audit,access).createSite(request)));
        var capture=ArgumentCaptor.forClass(AuditAppendCommand.class);verify(audit).appendDenied(capture.capture());
        var event=capture.getValue();assertEquals(AuditAction.SITE_CREATED,event.getAction());assertEquals(AuditObjectType.SITE,event.getObjectType());
        assertEquals(7L,event.getTenantId());assertEquals(19L,event.getActorUserId());assertNull(event.getObjectId());
        assertEquals("FORBIDDEN",event.getErrorCode());assertTrue(event.getChanges().isEmpty());
    }
    @Test void 修改失败审计不可用仍保留原始业务冲突() {
        var delegate=mock(SiteService.class);var audit=mock(AuditEventService.class);var access=mock(CurrentAccessService.class);
        var tenant=new TenantEntity();tenant.setId(7L);
        when(access.requireTenantAccess()).thenReturn(new TenantAccess(19L,tenant,21L,Set.of("TENANT_ADMIN")));
        var request=new SiteUpsertDto("S1","名称",null,false);
        var failure=new ResponseStatusException(HttpStatus.CONFLICT,"默认厂区不能停用");
        when(delegate.updateSite(100L,request)).thenThrow(failure);
        doThrow(new IllegalStateException("受控失败")).when(audit).appendFailure(any());
        assertSame(failure,assertThrows(ResponseStatusException.class,()->new AuditedSiteService(delegate,audit,access).updateSite(100L,request)));
        var capture=ArgumentCaptor.forClass(AuditAppendCommand.class);verify(audit).appendFailure(capture.capture());
        assertEquals(AuditAction.SITE_UPDATED,capture.getValue().getAction());
    }
}
