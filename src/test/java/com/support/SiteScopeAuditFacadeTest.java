package com.support;

import com.audit.service.AuditEventService;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.dto.tenant.SiteScopeUpdateDto;
import com.equipops.domain.entity.TenantEntity;
import com.tenant.service.SiteScopeService;
import com.tenant.service.impl.AuditedSiteScopeService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 范围失败审计必须在委托事务退出后执行，且不改变原始HTTP状态。 */
class SiteScopeAuditFacadeTest {
    @Test void 拒绝事件使用可信企业且不泄露目标对象或异常正文() {
        var delegate=mock(SiteScopeService.class);
        var audit=mock(AuditEventService.class);
        var access=mock(CurrentAccessService.class);
        var tenant=new TenantEntity();tenant.setId(7L);
        when(access.requireTenantAccess()).thenReturn(new TenantAccess(19L,tenant,21L,Set.of()));
        var request=new SiteScopeUpdateDto(0L,List.of(11L));
        var failure=new ResponseStatusException(HttpStatus.FORBIDDEN,"private-marker");
        when(delegate.updateScopes(100L,request)).thenThrow(failure);
        var service=new AuditedSiteScopeService(delegate,audit,access);
        assertSame(failure,assertThrows(ResponseStatusException.class,()->service.updateScopes(100L,request)));
        var capture=ArgumentCaptor.forClass(AuditAppendCommand.class);
        verify(audit).appendDenied(capture.capture());
        assertEquals(7L,capture.getValue().getTenantId());assertEquals(19L,capture.getValue().getActorUserId());
        assertNull(capture.getValue().getObjectId());assertTrue(capture.getValue().getChanges().isEmpty());
        assertEquals("FORBIDDEN",capture.getValue().getErrorCode());
    }
    @Test void 审计依赖失败仍抛原始版本冲突() {
        var delegate=mock(SiteScopeService.class);var audit=mock(AuditEventService.class);var access=mock(CurrentAccessService.class);
        var tenant=new TenantEntity();tenant.setId(7L);
        when(access.requireTenantAccess()).thenReturn(new TenantAccess(19L,tenant,21L,Set.of("TENANT_ADMIN")));
        var request=new SiteScopeUpdateDto(0L,List.of());var failure=new ResponseStatusException(HttpStatus.CONFLICT,"版本冲突");
        when(delegate.updateScopes(100L,request)).thenThrow(failure);
        doThrow(new IllegalStateException("private-marker")).when(audit).appendFailure(any());
        assertSame(failure,assertThrows(ResponseStatusException.class,()->new AuditedSiteScopeService(delegate,audit,access).updateScopes(100L,request)));
    }
}
