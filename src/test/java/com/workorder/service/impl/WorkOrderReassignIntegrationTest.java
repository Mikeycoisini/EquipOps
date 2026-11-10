package com.workorder.service.impl;
import com.EquipOpsApplication;
import com.audit.mapper.AuditEventMapper;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.workorder.WorkOrderReassignDto;
import com.equipops.domain.entity.*;
import com.equipops.domain.enums.*;
import com.workorder.service.WorkOrderReassignService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 每次调用独立真实事务，UUID夹具只允许预检后的equipops_a1_*库。 */
@SpringBootTest(classes=EquipOpsApplication.class)
@EnabledIfEnvironmentVariable(named="A4_MYSQL_TESTS",matches="true")
class WorkOrderReassignIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired WorkOrderReassignService service;
 @Autowired ObjectMapper json;
 @Autowired com.workorder.mapper.WorkOrderMapper orders;
 @MockitoBean CurrentAccessService access;
 @MockitoSpyBean AuditEventMapper auditMapper;
 long tenant,admin,first,next,member,site,team,order;
 @BeforeEach void fixture() {
  assertTrue(db.queryForObject("SELECT DATABASE()",String.class).matches("equipops_a1_[A-Za-z0-9_]+"));
  String key=UUID.randomUUID().toString().replace("-","");
  db.update("INSERT INTO tenants(tenant_code,name) VALUES(?,'A4 reassign')","r4_"+key);tenant=id("SELECT id FROM tenants WHERE tenant_code=?","r4_"+key);
  for(String suffix:List.of("a","b","c"))db.update("INSERT INTO users(account,password_hash,display_name) VALUES(?,'unused','A4 test')",suffix+key);
  admin=id("SELECT id FROM users WHERE account=?","a"+key);first=id("SELECT id FROM users WHERE account=?","b"+key);next=id("SELECT id FROM users WHERE account=?","c"+key);
  for(long user:new long[]{admin,first,next})db.update("INSERT INTO tenant_memberships(tenant_id,user_id) VALUES(?,?)",tenant,user);
  long adminMember=id("SELECT id FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,admin);
  member=id("SELECT id FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,next);
  long firstMember=id("SELECT id FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,first);
  db.update("INSERT INTO roles(role_code,name) SELECT 'TENANT_ADMIN','企业管理员' WHERE NOT EXISTS(SELECT 1 FROM roles WHERE role_code='TENANT_ADMIN')");
  db.update("INSERT INTO tenant_member_roles(tenant_id,membership_id,role_id) SELECT ?,?,id FROM roles WHERE role_code='TENANT_ADMIN'",tenant,adminMember);
  db.update("INSERT INTO sites(tenant_id,site_code,name,is_default) VALUES(?,'DEFAULT','A4 site',1)",tenant);site=id("SELECT id FROM sites WHERE tenant_id=?",tenant);
  for(long m:new long[]{firstMember,member})db.update("INSERT INTO tenant_member_site_scopes(tenant_id,membership_id,site_id,granted_by) VALUES(?,?,?,?)",tenant,m,site,admin);
  db.update("INSERT INTO teams(tenant_id,team_code,name,created_by) VALUES(?,'REPAIR','A4 team',?)",tenant,admin);team=id("SELECT id FROM teams WHERE tenant_id=?",tenant);
  db.update("INSERT INTO team_members(tenant_id,team_id,membership_id) VALUES(?,?,?)",tenant,team,member);
  db.update("INSERT INTO team_site_scopes(tenant_id,team_id,site_id) VALUES(?,?,?)",tenant,team,site);
  db.update("INSERT INTO equipment(tenant_id,site_id,asset_code,name) VALUES(?,?,?,'A4 equipment')",tenant,site,key);
  long equipment=id("SELECT id FROM equipment WHERE tenant_id=?",tenant);
  db.update("INSERT INTO work_orders(tenant_id,site_id,order_no,title,equipment_id,assignee_user_id) VALUES(?,?,?,'A4 work',?,?)",tenant,site,key,equipment,first);order=id("SELECT id FROM work_orders WHERE tenant_id=?",tenant);
  db.update("INSERT INTO work_order_logs(work_order_id,event_type,title,detail,actor_user_id) VALUES(?,'COMMENT','历史说明','保留旧责任历史',?)",order,first);
  db.update("INSERT INTO spare_parts(tenant_id,part_code,name) VALUES(?,?,'A4 material')",tenant,key);
  db.update("INSERT INTO work_order_parts(work_order_id,spare_part_id,issued_quantity,returned_quantity) SELECT ?,id,2,1 FROM spare_parts WHERE tenant_id=?",order,tenant);
  var company=new TenantEntity();company.setId(tenant);company.setEnabled(true);
  when(access.requireTenantAccess()).thenReturn(new TenantAccess(admin,company,adminMember,Set.of("TENANT_ADMIN")));
  when(access.requireTenantAdminTenantId()).thenReturn(tenant);when(access.requireActiveTenantId()).thenReturn(tenant);when(access.requireEnabledUserId()).thenReturn(admin);
 }
 @AfterEach void cleanup() {
  reset(auditMapper);
  if(tenant>0){
   db.update("DELETE FROM work_order_reassignments WHERE tenant_id=?",tenant);
   db.update("DELETE FROM work_order_parts WHERE work_order_id IN(SELECT id FROM work_orders WHERE tenant_id=?)",tenant);
   db.update("DELETE FROM work_orders WHERE tenant_id=?",tenant);
   db.update("DELETE FROM equipment WHERE tenant_id=?",tenant);db.update("DELETE FROM spare_parts WHERE tenant_id=?",tenant);
   db.update("DELETE FROM team_operations WHERE tenant_id=?",tenant);db.update("DELETE FROM team_members WHERE tenant_id=?",tenant);db.update("DELETE FROM team_site_scopes WHERE tenant_id=?",tenant);db.update("DELETE FROM teams WHERE tenant_id=?",tenant);
   db.update("DELETE FROM audit_events WHERE tenant_id=?",tenant);db.update("DELETE FROM tenant_member_site_scopes WHERE tenant_id=?",tenant);db.update("DELETE FROM sites WHERE tenant_id=?",tenant);
   db.update("DELETE FROM tenant_member_roles WHERE tenant_id=?",tenant);db.update("DELETE FROM tenant_memberships WHERE tenant_id=?",tenant);db.update("DELETE FROM tenants WHERE id=?",tenant);
  }
  for(long user:new long[]{admin,first,next})if(user>0)db.update("DELETE FROM users WHERE id=?",user);
 }
 long id(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
 WorkOrderReassignDto request(long version,Long targetTeam){return new WorkOrderReassignDto(UUID.randomUUID().toString(),version,next,targetTeam,"交接检修责任");}
 void rejects(int status,WorkOrderReassignDto r){var e=assertThrows(ResponseStatusException.class,()->service.reassign(order,r));assertEquals(status,e.getStatusCode().value());}
 @Test void pendingReassignmentReturnsVersionAndPreservesHistoricalMaterials() {
  var result=assertDoesNotThrow(()->service.reassign(order,request(0,team)));
  var tree=json.valueToTree(result);assertNotNull(tree.get("version"),"改派结果必须含乐观版本");assertEquals(1,tree.get("version").asLong());
  assertEquals(next,result.getAssigneeUserId());assertEquals(WorkOrderStatus.PENDING,result.getStatus());
  assertEquals(1,id("SELECT version FROM work_orders WHERE id=?",order));
  assertEquals(2,id("SELECT COUNT(*) FROM work_order_logs WHERE work_order_id=?",order));
  assertEquals(2,id("SELECT issued_quantity FROM work_order_parts WHERE work_order_id=?",order));
  assertEquals(1,id("SELECT returned_quantity FROM work_order_parts WHERE work_order_id=?",order));
  assertEquals(1,id("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='WORK_ORDER_REASSIGNED' AND outcome='SUCCESS'",tenant));
 }
 @Test void idempotentReplayReturnsOriginalResultAfterLaterReassignment() {
  var initial=request(0,team);service.reassign(order,initial);
  var second=request(1,null);second.setTargetAssigneeUserId(first);service.reassign(order,second);
  var replay=service.reassign(order,initial);assertEquals(next,replay.getAssigneeUserId());
  assertNotNull(json.valueToTree(replay).get("version"));assertEquals(1,json.valueToTree(replay).get("version").asLong());
  assertEquals(2,id("SELECT COUNT(*) FROM work_order_reassignments WHERE tenant_id=?",tenant));
  initial.setReason("不同原因");rejects(409,initial);
 }
 @Test void teamCannotExpandPersonalScopeAndBothTeamConditionsAreRequired() {
  db.update("DELETE FROM tenant_member_site_scopes WHERE membership_id=?",member);rejects(400,request(0,team));
  db.update("INSERT INTO tenant_member_site_scopes(tenant_id,membership_id,site_id,granted_by) VALUES(?,?,?,?)",tenant,member,site,admin);
  db.update("DELETE FROM team_site_scopes WHERE team_id=?",team);rejects(400,request(0,team));
  db.update("INSERT INTO team_site_scopes(tenant_id,team_id,site_id) VALUES(?,?,?)",tenant,team,site);
  db.update("DELETE FROM team_members WHERE team_id=?",team);rejects(400,request(0,team));
  assertEquals(0,id("SELECT version FROM work_orders WHERE id=?",order));
 }
 @Test void oldWorkflowTransitionAdvancesReassignmentVersion() {
  assertEquals(1,orders.startRepair(tenant,order,first));
  assertEquals(1,id("SELECT version FROM work_orders WHERE id=?",order));
  rejects(409,request(0,null));
 }
 @Test void completedAndAwaitingAcceptanceOrStaleVersionCannotReassign() {
  for(String state:List.of("AWAITING_ACCEPTANCE","COMPLETED")){db.update("UPDATE work_orders SET status=? WHERE id=?",state,order);rejects(409,request(0,team));}
  db.update("UPDATE work_orders SET status='IN_PROGRESS' WHERE id=?",order);rejects(409,request(9,team));
  var result=service.reassign(order,request(0,null));assertEquals(WorkOrderStatus.IN_PROGRESS,result.getStatus());
 }
 @Test void auditFailureRollsBackResponsibilityHistoryAndIdempotencyRecord() {
  doThrow(new org.springframework.dao.DataIntegrityViolationException("A4 controlled audit failure")).when(auditMapper).insert(argThat((AuditEventEntity e)->e!=null&&e.getAction()==AuditAction.WORK_ORDER_REASSIGNED&&e.getOutcome()==AuditOutcome.SUCCESS));
  assertThrows(RuntimeException.class,()->service.reassign(order,request(0,team)));
  assertEquals(first,id("SELECT assignee_user_id FROM work_orders WHERE id=?",order));assertEquals(0,id("SELECT version FROM work_orders WHERE id=?",order));
  assertEquals(1,id("SELECT COUNT(*) FROM work_order_logs WHERE work_order_id=?",order));assertEquals(0,id("SELECT COUNT(*) FROM work_order_reassignments WHERE tenant_id=?",tenant));
  assertEquals(1,id("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='WORK_ORDER_REASSIGNED' AND outcome='FAILURE'",tenant));
 }
 @Test void concurrentSameVersionHasOneWinner() throws Exception {
  var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try{
   var futures=new ArrayList<Future<Integer>>();
   for(Long targetTeam:new Long[]{team,null})futures.add(pool.submit(()->{start.await();try{service.reassign(order,request(0,targetTeam));return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
   start.countDown();var statuses=new ArrayList<Integer>();for(var future:futures)statuses.add(future.get(10,TimeUnit.SECONDS));Collections.sort(statuses);
   assertEquals(List.of(200,409),statuses);assertEquals(1,id("SELECT version FROM work_orders WHERE id=?",order));
  }finally{pool.shutdownNow();}
 }
}
