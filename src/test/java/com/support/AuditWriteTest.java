package com.support;
import com.audit.mapper.AuditEventMapper;
import com.audit.service.impl.AuditEventServiceImpl;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.entity.AuditEventEntity;
import com.equipops.domain.enums.*;
import com.equipops.domain.vo.AuditEventDetailVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AuditWriteTest {
 private AuditAppendCommand command() {
  return new AuditAppendCommand(1L,2L,AuditAction.MEMBER_STATUS_CHANGED,AuditObjectType.MEMBER,3L,
    Map.of("field","enabled","before",true,"after",false,"note","private-marker","password","private-password"),"FORBIDDEN",null);
 }
 @Test void failuresAndDenialsUseDistinctOutcomesAndSafeCodes() {
  var mapper=mock(AuditEventMapper.class);when(mapper.insert(any(AuditEventEntity.class))).thenReturn(1);
  var service=new AuditEventServiceImpl(mapper,mock(CurrentAccessService.class),new ObjectMapper());
  service.appendFailure(command());service.appendDenied(command());
  var captured=ArgumentCaptor.forClass(AuditEventEntity.class);verify(mapper,times(2)).insert(captured.capture());
  assertEquals(AuditOutcome.FAILURE,captured.getAllValues().get(0).getOutcome());
  assertEquals(AuditOutcome.DENIED,captured.getAllValues().get(1).getOutcome());
  assertEquals("{\"errorCode\":\"FORBIDDEN\"}", captured.getValue().getChangesJson());
  assertFalse(captured.getValue().getChangesJson().contains("private"));
 }
 @Test void successDropsUnknownTextAndDoesNotKeepErrorCode() {
  var mapper=mock(AuditEventMapper.class);when(mapper.insert(any(AuditEventEntity.class))).thenReturn(1);
  var service=new AuditEventServiceImpl(mapper,mock(CurrentAccessService.class),new ObjectMapper());
  service.appendSuccess(command());var captured=ArgumentCaptor.forClass(AuditEventEntity.class);verify(mapper).insert(captured.capture());
  assertFalse(captured.getValue().getChangesJson().contains("private"));
  assertFalse(captured.getValue().getChangesJson().contains("FORBIDDEN"));
 }
 @Test void legacyDetailIsFilteredBeforeReturning() {
  var mapper=mock(AuditEventMapper.class);var access=mock(CurrentAccessService.class);when(access.requireTenantAdminTenantId()).thenReturn(1L);
  var row=new AuditEventDetailVO();row.setAction(AuditAction.MEMBER_STATUS_CHANGED);
  row.setChangesJson("{\"field\":\"password\",\"before\":\"private\",\"after\":\"private\",\"note\":\"private\"}");
  when(mapper.selectDetailById(1L,3L)).thenReturn(row);
  var result=new AuditEventServiceImpl(mapper,access,new ObjectMapper()).detail(3L);
  assertFalse(result.getChanges().toString().contains("private"));assertNull(result.getSummary());
 }
 @Test void legacyFailureReadOnlyReturnsSafeErrorCode() {
  var mapper=mock(AuditEventMapper.class);var access=mock(CurrentAccessService.class);when(access.requireTenantAdminTenantId()).thenReturn(1L);
  var row=new AuditEventDetailVO();row.setAction(AuditAction.MEMBER_STATUS_CHANGED);row.setOutcome(AuditOutcome.DENIED);
  row.setChangesJson("{\"field\":\"enabled\",\"before\":true,\"after\":false,\"errorCode\":\"FORBIDDEN\"}");
  when(mapper.selectDetailById(1L,3L)).thenReturn(row);
  var result=new AuditEventServiceImpl(mapper,access,new ObjectMapper()).detail(3L);
  assertEquals(Map.of("errorCode","FORBIDDEN"),result.getChanges());assertNull(result.getSummary());
 }
}
