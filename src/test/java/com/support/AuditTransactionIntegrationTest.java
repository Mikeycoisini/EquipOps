package com.support;

import com.EquipOpsApplication;
import com.audit.mapper.AuditEventMapper;
import com.equipops.domain.entity.AuditEventEntity;
import com.equipops.domain.enums.AuditOutcome;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.dto.tenant.*;
import com.tenant.service.TenantMemberService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在预检后的A1隔离库执行真实MySQL事务，清理只针对本类生成的UUID对象。 */
@SpringBootTest(classes=EquipOpsApplication.class)
@EnabledIfEnvironmentVariable(named="A2_MYSQL_TESTS",matches="true")
class AuditTransactionIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired TenantMemberService service;
 @MockitoBean CurrentAccessService access;
 @MockitoSpyBean AuditEventMapper auditMapper;
 long tenant,admin,member,target,membership,role;
 @BeforeEach void fixture() {
  assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("equipops_a1_"));
  String key=UUID.randomUUID().toString().replace("-","");
  db.update("INSERT INTO roles(role_code,name) SELECT 'TENANT_ADMIN','企业管理员' WHERE NOT EXISTS(SELECT 1 FROM roles WHERE role_code='TENANT_ADMIN')");
  role=id("SELECT id FROM roles WHERE role_code='TENANT_ADMIN'");
  db.update("INSERT INTO users(account,password_hash,display_name) VALUES(?, 'unused-test-hash','A2 fixture')","a2admin_"+key);
  admin=id("SELECT id FROM users WHERE account=?","a2admin_"+key);
  db.update("INSERT INTO users(account,password_hash,display_name) VALUES(?, 'unused-test-hash','A2 fixture')","a2member_"+key);
  member=id("SELECT id FROM users WHERE account=?","a2member_"+key);
  db.update("INSERT INTO users(account,password_hash,display_name) VALUES(?, 'unused-test-hash','A2 fixture')","a2target_"+key);
  target=id("SELECT id FROM users WHERE account=?","a2target_"+key);
  db.update("INSERT INTO tenants(tenant_code,name) VALUES(?,'A2 fixture')","A2_"+key);
  tenant=id("SELECT id FROM tenants WHERE tenant_code=?","A2_"+key);
  db.update("INSERT INTO tenant_memberships(tenant_id,user_id) VALUES(?,?),(?,?)",tenant,admin,tenant,member);
  membership=id("SELECT id FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,member);
  long adminMembership=id("SELECT id FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,admin);
  db.update("INSERT INTO tenant_member_roles(tenant_id,membership_id,role_id) VALUES(?,?,?)",tenant,adminMembership,role);
  var company=new TenantEntity();company.setId(tenant);company.setEnabled(true);
  when(access.requireTenantAccess()).thenReturn(new TenantAccess(admin,company,adminMembership,Set.of("TENANT_ADMIN")));
  when(access.requireEnabledUserId()).thenReturn(admin);
  when(access.requireTenantAdminTenantId()).thenReturn(tenant);
 }
 @AfterEach void cleanup() {
  if(tenant>0) {
   db.update("DELETE FROM audit_events WHERE tenant_id=?",tenant);
   db.update("DELETE FROM tenant_member_roles WHERE tenant_id=?",tenant);
   db.update("DELETE FROM tenant_memberships WHERE tenant_id=?",tenant);
   db.update("DELETE FROM tenants WHERE id=?",tenant);
  }
  for(long user:new long[]{admin,member,target})if(user>0)db.update("DELETE FROM users WHERE id=?",user);
 }
 @Test void statusChangeAndIdempotentRetryHaveOneSuccess() {
  var request=new TenantMemberStatusUpdateDto();request.setEnabled(false);
  service.updateMemberStatus(membership,request);service.updateMemberStatus(membership,request);
  assertEquals(0,id("SELECT enabled FROM tenant_memberships WHERE id=?",membership));
  assertEquals(1,count("SUCCESS"));
 }
 @Test void successfulAuditFailureRollsBackBusinessAndRecordsFailureSeparately() {
  // 仅测试配置的受控审计写入故障，不向产品配置加入故障开关、不要求SUPER权限。
  doThrow(new org.springframework.dao.DataIntegrityViolationException("A2 controlled failure"))
      .when(auditMapper).insert(argThat((AuditEventEntity event)->event!=null && event.getTenantId()==tenant && event.getOutcome()==AuditOutcome.SUCCESS));
  var request=new TenantMemberStatusUpdateDto();request.setEnabled(false);
  assertThrows(RuntimeException.class,()->service.updateMemberStatus(membership,request));
  assertEquals(1,id("SELECT enabled FROM tenant_memberships WHERE id=?",membership));
  assertEquals(0,count("SUCCESS"));assertEquals(1,count("FAILURE"));
 }
 @Test void refusalIsIndependentAndDoesNotChangeMembership() {
  when(access.requireTenantAdminTenantId()).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"not administrator"));
  var request=new TenantMemberStatusUpdateDto();request.setEnabled(false);
  assertThrows(ResponseStatusException.class,()->service.updateMemberStatus(membership,request));
  assertEquals(1,id("SELECT enabled FROM tenant_memberships WHERE id=?",membership));
  assertEquals(1,count("DENIED"));assertEquals(0,count("SUCCESS"));
 }
 @Test void addAndRoleChangePersistAuditsInTheirBusinessTransactions() {
  var create=new TenantMemberCreateDto();create.setUserId(target);create.setRoleIds(Set.of());
  var added=service.addMember(create);assertEquals(1,count("SUCCESS"));
  var roles=new TenantMemberRolesUpdateDto();roles.setRoleIds(Set.of(role));
  service.updateMemberRoles(added.getId(),roles);
  assertEquals(2,count("SUCCESS"));
  assertEquals(1,id("SELECT COUNT(*) FROM tenant_member_roles WHERE tenant_id=? AND membership_id=?",tenant,added.getId()));
  service.updateMemberRoles(added.getId(),roles);assertEquals(2,count("SUCCESS"));
 }
 private long id(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
 private long count(String outcome){return id("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND outcome=?",tenant,outcome);}
}
