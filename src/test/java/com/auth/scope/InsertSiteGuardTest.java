package com.auth.scope;
import com.auth.service.CurrentAccessService;
import com.equipment.mapper.EquipmentMapper;
import com.equipment.service.impl.EquipmentServiceImpl;
import com.equipops.domain.dto.workorder.WorkOrderCreateDto;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.vo.SiteVO;
import com.tenant.mapper.TenantMembershipMapper;
import com.tenant.service.SiteService;
import com.workorder.mapper.*;
import com.workorder.service.impl.WorkOrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InsertSiteGuardTest {
 @Mock CurrentAccessService access;
 @Mock EquipmentMapper equipment;
 @Mock SiteService sites;
 @Mock SiteWriteAccessService guard;
 @Mock WorkOrderMapper orders;
 @Mock WorkOrderLogMapper logs;
 @Mock UserMapper users;
 @Mock TenantMembershipMapper memberships;
 @InjectMocks EquipmentServiceImpl equipmentService;
 @InjectMocks WorkOrderServiceImpl workOrders;
 @Test void equipmentInsertCannotSkipCurrentWriteAuthorization() {
  org.springframework.test.util.ReflectionTestUtils.setField(equipmentService,"siteWriteAccess",guard);
  when(access.requireActiveTenantId()).thenReturn(71L);
  var site=new SiteVO();site.setId(10L);when(sites.getDefaultSites()).thenReturn(List.of(site));
  doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(guard).lockSite(10L);
  var error=assertThrows(ResponseStatusException.class,()->equipmentService.addEquipment(new EquipmentEntity()));
  assertEquals(403,error.getStatusCode().value());verify(equipment,never()).insert(any(EquipmentEntity.class));
 }
 @Test void workOrderInsertCannotSkipParentSiteAuthorization() {
  org.springframework.test.util.ReflectionTestUtils.setField(workOrders,"siteWriteAccess",guard);
  when(access.requireActiveTenantId()).thenReturn(71L);
  var parent=new EquipmentEntity();parent.setId(20L);parent.setTenantId(71L);parent.setSiteId(10L);
  when(equipment.selectOne(any())).thenReturn(parent);
  doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(guard).lockSite(10L);
  var request=new WorkOrderCreateDto();request.setTitle("测试报修");request.setEquipmentId(20L);
  var error=assertThrows(ResponseStatusException.class,()->workOrders.reportRepair(request));
  assertEquals(403,error.getStatusCode().value());verify(orders,never()).insert(any(com.equipops.domain.entity.WorkOrderEntity.class));
 }
}
