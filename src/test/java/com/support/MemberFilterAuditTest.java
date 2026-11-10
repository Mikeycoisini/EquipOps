package com.support;
import com.audit.service.AuditEventService;
import com.auth.security.*;
import com.auth.service.CurrentAccessService;
import com.auth.model.TenantAccess;
import com.equipops.domain.entity.TenantEntity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class MemberFilterAuditTest {
 @Test void knownWriteDenialIsRecordedButRequestStillStopsWith403() throws Exception {
  var access=mock(CurrentAccessService.class);var audit=mock(AuditEventService.class);
  when(access.requireEnabledUserId()).thenReturn(2L);
  when(access.requireTenantAdminTenantId()).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"无权限"));
  var company=new TenantEntity();company.setId(1L);
  when(access.requireTenantAccess()).thenReturn(new TenantAccess(2L,company,3L,Set.of()));
  var filter=new AccessValidationFilter(access,new ApiSecurityResponse(new ObjectMapper()),audit);
  var request=new MockHttpServletRequest("PATCH","/api/tenant-members/99/status");request.setServletPath("/api/tenant-members/99/status");
  var response=new MockHttpServletResponse();var chain=mock(jakarta.servlet.FilterChain.class);
  filter.doFilter(request,response,chain);
  assertEquals(403,response.getStatus());verifyNoInteractions(chain);
  verify(audit).appendDenied(argThat(c->c.getTenantId()==1L && c.getActorUserId()==2L && c.getObjectId()==null));
 }
 @Test void untrustedMembershipDoesNotCreateSpeculativeAudit() throws Exception {
  var access=mock(CurrentAccessService.class);var audit=mock(AuditEventService.class);
  when(access.requireEnabledUserId()).thenReturn(2L);
  when(access.requireTenantAdminTenantId()).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
  when(access.requireTenantAccess()).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
  var filter=new AccessValidationFilter(access,new ApiSecurityResponse(new ObjectMapper()),audit);
  var request=new MockHttpServletRequest("PATCH","/api/tenant-members/99/status");request.setServletPath("/api/tenant-members/99/status");
  var response=new MockHttpServletResponse();filter.doFilter(request,response,mock(jakarta.servlet.FilterChain.class));
  assertEquals(403,response.getStatus());verifyNoInteractions(audit);
 }
 @Test void contextStorageFailureMustNotReplaceOriginal403() throws Exception {
  var access=mock(CurrentAccessService.class);var audit=mock(AuditEventService.class);
  when(access.requireEnabledUserId()).thenReturn(2L);
  when(access.requireTenantAdminTenantId()).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
  when(access.requireTenantAccess()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private-db-text"));
  var filter=new AccessValidationFilter(access,new ApiSecurityResponse(new ObjectMapper()),audit);
  var request=new MockHttpServletRequest("PATCH","/api/tenant-members/99/status");request.setServletPath("/api/tenant-members/99/status");
  var response=new MockHttpServletResponse();filter.doFilter(request,response,mock(jakarta.servlet.FilterChain.class));
  assertEquals(403,response.getStatus());verifyNoInteractions(audit);
 }
 @Test void scopePutFilterDenialRecordsKnownScopeActionWithoutTargetOrBody() throws Exception {
  var access=mock(CurrentAccessService.class);var audit=mock(AuditEventService.class);
  when(access.requireEnabledUserId()).thenReturn(2L);
  when(access.requireTenantAdminTenantId()).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"无权限"));
  var company=new TenantEntity();company.setId(1L);
  when(access.requireTenantAccess()).thenReturn(new TenantAccess(2L,company,3L,Set.of()));
  var filter=new AccessValidationFilter(access,new ApiSecurityResponse(new ObjectMapper()),audit);
  var request=new MockHttpServletRequest("PUT","/api/tenant-members/99/site-scopes");request.setServletPath("/api/tenant-members/99/site-scopes");
  request.setContent("private-body-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var response=new MockHttpServletResponse();var chain=mock(jakarta.servlet.FilterChain.class);
  filter.doFilter(request,response,chain);
  assertEquals(403,response.getStatus());verifyNoInteractions(chain);
  verify(audit).appendDenied(argThat(c->c.getTenantId()==1L && c.getActorUserId()==2L && c.getObjectId()==null
    && c.getAction()==com.equipops.domain.enums.AuditAction.MEMBER_SITE_SCOPES_CHANGED
    && c.getObjectType()==com.equipops.domain.enums.AuditObjectType.MEMBER && c.getChanges().isEmpty()));
 }
 @Test void knownSiteWriteFilterDenialUsesSiteObjectType() throws Exception {
  for(String method:java.util.List.of("POST","PUT")) {
   var access=mock(CurrentAccessService.class);var audit=mock(AuditEventService.class);
   when(access.requireEnabledUserId()).thenReturn(2L);
   when(access.requireActiveTenantId()).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"企业范围不可用"));
   var company=new TenantEntity();company.setId(1L);
   when(access.requireTenantAccess()).thenReturn(new TenantAccess(2L,company,3L,Set.of()));
   var filter=new AccessValidationFilter(access,new ApiSecurityResponse(new ObjectMapper()),audit);
   String path=method.equals("POST")?"/api/sites":"/api/sites/99";
   var request=new MockHttpServletRequest(method,path);request.setServletPath(path);
   var response=new MockHttpServletResponse();filter.doFilter(request,response,mock(jakarta.servlet.FilterChain.class));
   assertEquals(403,response.getStatus());
   var action=method.equals("POST")?com.equipops.domain.enums.AuditAction.SITE_CREATED:com.equipops.domain.enums.AuditAction.SITE_UPDATED;
   verify(audit).appendDenied(argThat(c->c.getAction()==action && c.getObjectType()==com.equipops.domain.enums.AuditObjectType.SITE
     && c.getObjectId()==null && c.getChanges().isEmpty()));
  }
 }
}
