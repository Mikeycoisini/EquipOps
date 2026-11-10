package com.support;
import com.audit.mapper.AuditEventMapper;
import com.audit.service.impl.AuditEventServiceImpl;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.entity.AuditEventEntity;
import com.equipops.domain.enums.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TeamReassignAuditTest {
 String write(AuditAction action,Map<String,Object> changes) {
  var mapper=mock(AuditEventMapper.class);when(mapper.insert(any(AuditEventEntity.class))).thenReturn(1);
  new AuditEventServiceImpl(mapper,mock(CurrentAccessService.class),new ObjectMapper()).appendSuccess(new AuditAppendCommand(7L,9L,action,AuditObjectType.TEAM,11L,changes,null,null));
  var captured=ArgumentCaptor.forClass(AuditEventEntity.class);verify(mapper).insert(captured.capture());return captured.getValue().getChangesJson();
 }
 @Test void teamCreationAndUpdateOnlyKeepFiniteMetadata() {
  String created=write(AuditAction.TEAM_CREATED,Map.of("enabled",true,"name","private-text"));assertNotNull(created);assertTrue(created.contains("enabled"));assertFalse(created.contains("private-text"));
  String updated=write(AuditAction.TEAM_UPDATED,Map.of("changedFields",List.of("name","enabled"),"beforeEnabled",true,"afterEnabled",false,"name","private-text"));assertNotNull(updated);assertTrue(updated.contains("changedFields"));assertFalse(updated.contains("private-text"));
 }
 @Test void membershipAndSiteChangesPreserveStringIdentifierSets() {
  for(var action:List.of(AuditAction.TEAM_MEMBERS_CHANGED,AuditAction.TEAM_SITE_SCOPES_CHANGED)){
   String field=action==AuditAction.TEAM_MEMBERS_CHANGED?"membershipIds":"siteIds";
   String value=write(action,Map.of("field",field,"before",List.of("9007199254740993"),"after",List.of(),"version",1L,"body","private-text"));
   assertNotNull(value);assertTrue(value.contains("9007199254740993"));assertFalse(value.contains("private-text"));
  }
 }
 @Test void reassignmentKeepsOnlyIdsVersionsAndReasonMarker() {
  var changes=new LinkedHashMap<String,Object>();changes.put("beforeAssigneeUserId",null);changes.put("afterAssigneeUserId","9007199254740993");changes.put("beforeTeamId",null);changes.put("afterTeamId","11");changes.put("beforeVersion",0L);changes.put("version",1L);changes.put("reasonPresent",true);changes.put("reason","private-text");
  String value=write(AuditAction.WORK_ORDER_REASSIGNED,changes);assertNotNull(value);assertTrue(value.contains("9007199254740993"));assertTrue(value.contains("reasonPresent"));assertFalse(value.contains("private-text"));
 }
 @Test void malformedPayloadCannotSmuggleTextThroughIdentifierOrVersion() {
  assertNull(write(AuditAction.TEAM_MEMBERS_CHANGED,Map.of("field","membershipIds","before",List.of("private-text"),"after",List.of(),"version",1L)));
  assertNull(write(AuditAction.WORK_ORDER_REASSIGNED,Map.of("afterAssigneeUserId","private-text","reasonPresent",true,"version",1.5)));
 }
}
