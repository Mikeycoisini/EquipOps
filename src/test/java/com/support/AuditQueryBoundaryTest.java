package com.support;
import com.audit.mapper.AuditEventMapper;
import com.audit.service.impl.AuditEventServiceImpl;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditEventPageQueryDto;
import com.equipops.domain.vo.AuditEventVO;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AuditQueryBoundaryTest {
 @Test void zeroIdIsRejectedBeforeQuery() {
  var mapper=mock(AuditEventMapper.class);var access=mock(CurrentAccessService.class);
  when(access.requireTenantAdminTenantId()).thenReturn(1L);
  var service=new AuditEventServiceImpl(mapper,access,new ObjectMapper());
  assertEquals(400,assertThrows(ResponseStatusException.class,()->service.detail(0L)).getStatusCode().value());
  verifyNoInteractions(mapper);
 }
 @Test void overflowingPaginationIsRejected() {
  var mapper=mock(AuditEventMapper.class);var access=mock(CurrentAccessService.class);
  when(access.requireTenantAdminTenantId()).thenReturn(1L);
  var query=new AuditEventPageQueryDto();query.setCurrent(Long.MAX_VALUE);query.setSize(100);
  var service=new AuditEventServiceImpl(mapper,access,new ObjectMapper());
  assertEquals(400,assertThrows(ResponseStatusException.class,()->service.page(query)).getStatusCode().value());
  verifyNoInteractions(mapper);
 }
 @Test void listHasChangeSummary() {
  var mapper=mock(AuditEventMapper.class);var access=mock(CurrentAccessService.class);
  when(access.requireTenantAdminTenantId()).thenReturn(1L);
  var query=new AuditEventPageQueryDto();var row=new AuditEventVO();
  row.setChangesJson("{\"field\":\"enabled\",\"before\":true,\"after\":false}");
  when(mapper.countByQuery(1L,query)).thenReturn(1L);
  when(mapper.selectPage(1L,query,0L,20L)).thenReturn(List.of(row));
  var service=new AuditEventServiceImpl(mapper,access,new ObjectMapper());
  assertEquals("enabled：[true] -> [false]",service.page(query).getRecords().get(0).getSummary());
 }
 @Test void sensitiveOrNestedSummaryIsNotRendered() {
  var mapper=mock(AuditEventMapper.class);var access=mock(CurrentAccessService.class);
  when(access.requireTenantAdminTenantId()).thenReturn(1L);
  var query=new AuditEventPageQueryDto();var row=new AuditEventVO();
  row.setChangesJson("{\"field\":\"password\",\"before\":\"private-before\",\"after\":\"private-after\"}");
  when(mapper.countByQuery(1L,query)).thenReturn(1L);
  when(mapper.selectPage(1L,query,0L,20L)).thenReturn(List.of(row));
  var service=new AuditEventServiceImpl(mapper,access,new ObjectMapper());
  assertNull(service.page(query).getRecords().get(0).getSummary());
  row.setChangesJson("{\"field\":\"enabled\",\"before\":{\"token\":\"private\"},\"after\":false}");
  assertNull(service.page(query).getRecords().get(0).getSummary());
 }
}
