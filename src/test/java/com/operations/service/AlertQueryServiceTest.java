package com.operations.service;
import com.auth.service.CurrentAccessService;
import com.operations.mapper.AlertEventMapper;
import com.operations.service.impl.AlertQueryServiceImpl;
import com.equipops.domain.dto.operations.AlertPageQueryDto;
import com.equipops.domain.vo.AlertEventVO;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AlertQueryServiceTest {
 @Test void pageUsesTrustedTenantAndRejectsBadPagination() {
  var access=mock(CurrentAccessService.class);var mapper=mock(AlertEventMapper.class);
  var service=new AlertQueryServiceImpl(access,mapper);when(access.requireTenantAdminTenantId()).thenReturn(11L);
  when(mapper.selectEventPage(any(),eq(11L),any())).thenAnswer(a->a.getArgument(0));
  var q=new AlertPageQueryDto();service.page(q);
  verify(mapper).selectEventPage(any(),eq(11L),same(q));
  q.setSize(101);assertThrows(ResponseStatusException.class,()->service.page(q));
 }
 @Test void deniedAdminNeverQueries() {
  var access=mock(CurrentAccessService.class);var mapper=mock(AlertEventMapper.class);
  when(access.requireTenantAdminTenantId()).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
  assertThrows(ResponseStatusException.class,()->new AlertQueryServiceImpl(access,mapper).detail(9L));
  verifyNoInteractions(mapper);
 }
 @Test void absentOrOtherTenantDetailIs404() {
  var access=mock(CurrentAccessService.class);var mapper=mock(AlertEventMapper.class);
  when(access.requireTenantAdminTenantId()).thenReturn(11L);
  var service=new AlertQueryServiceImpl(access,mapper);
  assertEquals(404,assertThrows(ResponseStatusException.class,()->service.detail(9L)).getStatusCode().value());
  verify(mapper).selectEventDetail(11L,9L);
  var vo=new AlertEventVO();vo.setId(9L);when(mapper.selectEventDetail(11L,9L)).thenReturn(vo);
  assertSame(vo,service.detail(9L));
 }
}
