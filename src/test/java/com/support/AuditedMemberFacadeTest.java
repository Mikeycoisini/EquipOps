package com.support;
import com.audit.service.AuditEventService;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.dto.tenant.TenantMemberStatusUpdateDto;
import com.equipops.domain.enums.AuditOutcome;
import com.tenant.service.TenantMemberService;
import com.tenant.service.impl.AuditedTenantMemberService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AuditedMemberFacadeTest {
 @Test void rejectedActionRecordsTrustedTenantAfterDelegateFailure() {
  var delegate=mock(TenantMemberService.class);var audit=mock(AuditEventService.class);var access=mock(CurrentAccessService.class);
  var tenant=new TenantEntity();tenant.setId(11L);
  when(access.requireTenantAccess()).thenReturn(new TenantAccess(22L,tenant,33L,Set.of()));
  var request=new TenantMemberStatusUpdateDto();request.setEnabled(false);
  var rejected=new ResponseStatusException(HttpStatus.FORBIDDEN,"private-business-text");
  when(delegate.updateMemberStatus(99L,request)).thenThrow(rejected);
  var service=new AuditedTenantMemberService(delegate,audit,access);
  assertSame(rejected,assertThrows(ResponseStatusException.class,()->service.updateMemberStatus(99L,request)));
  verify(audit).appendDenied(argThat(c->c.getTenantId()==11L && c.getActorUserId()==22L && c.getObjectId()==null
      && "FORBIDDEN".equals(c.getErrorCode()) && c.getChanges().isEmpty()));
 }
 @Test void auditOutageDoesNotReplaceOriginalFailure() {
  var delegate=mock(TenantMemberService.class);var audit=mock(AuditEventService.class);var access=mock(CurrentAccessService.class);
  var tenant=new TenantEntity();tenant.setId(11L);
  when(access.requireTenantAccess()).thenReturn(new TenantAccess(22L,tenant,33L,Set.of("TENANT_ADMIN")));
  var request=new TenantMemberStatusUpdateDto();request.setEnabled(false);
  var failure=new IllegalStateException("private-business-text");
  when(delegate.updateMemberStatus(99L,request)).thenThrow(failure);
  doThrow(new IllegalStateException("private-audit-text")).when(audit).appendFailure(any());
  var service=new AuditedTenantMemberService(delegate,audit,access);
  assertSame(failure,assertThrows(IllegalStateException.class,()->service.updateMemberStatus(99L,request)));
 }
}
