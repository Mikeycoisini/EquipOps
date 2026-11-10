package com.team;

import com.EquipOpsApplication;
import com.audit.mapper.AuditEventMapper;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipment.mapper.EquipmentMapper;
import com.equipops.domain.entity.*;
import com.equipops.domain.enums.*;
import com.team.dto.*;
import com.team.service.TeamService;
import com.team.vo.TeamVO;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A4隔离MySQL验证团队快照、权限、交接约束及成功审计失败原子回滚；只清理UUID夹具企业。 */
@SpringBootTest(classes=EquipOpsApplication.class)
@EnabledIfEnvironmentVariable(named="A4_MYSQL_TESTS",matches="true")
class TeamTransactionIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired TeamService service;
    @Autowired EquipmentMapper equipment;
    @MockitoBean CurrentAccessService access;
    @MockitoSpyBean AuditEventMapper auditMapper;
    long tenant,other,admin,user,adminMember,member,site1,site2,otherSite,equipmentId,hiddenEquipment,role;
    String key;
    private String request(){return UUID.randomUUID().toString();}
    @BeforeEach void fixture() {
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).matches("equipops_a1_[A-Za-z0-9_]+"));
        key=UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO roles(role_code,name) SELECT 'TENANT_ADMIN','企业管理员' WHERE NOT EXISTS(SELECT 1 FROM roles WHERE role_code='TENANT_ADMIN')");
        role=id("SELECT id FROM roles WHERE role_code='TENANT_ADMIN'");
        db.update("INSERT INTO users(account,password_hash,display_name) VALUES(?,'unused-test-hash','A4 team admin'),(?,'unused-test-hash','A4 team user')","a4team_admin_"+key,"a4team_user_"+key);
        admin=id("SELECT id FROM users WHERE account=?","a4team_admin_"+key);user=id("SELECT id FROM users WHERE account=?","a4team_user_"+key);
        db.update("INSERT INTO tenants(tenant_code,name) VALUES(?,'A4 team'),(?,'A4 foreign')","a4team_"+key,"a4foreign_"+key);
        tenant=id("SELECT id FROM tenants WHERE tenant_code=?","a4team_"+key);other=id("SELECT id FROM tenants WHERE tenant_code=?","a4foreign_"+key);
        db.update("INSERT INTO tenant_memberships(tenant_id,user_id) VALUES(?,?),(?,?)",tenant,admin,tenant,user);
        adminMember=id("SELECT id FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,admin);member=id("SELECT id FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,user);
        db.update("INSERT INTO tenant_member_roles(tenant_id,membership_id,role_id) VALUES(?,?,?)",tenant,adminMember,role);
        db.update("INSERT INTO sites(tenant_id,site_code,name,is_default) VALUES(?,'ONE','A4 team one',1),(?,'TWO','A4 team two',0),(?,'OTHER','A4 foreign',1)",tenant,tenant,other);
        site1=id("SELECT id FROM sites WHERE tenant_id=? AND site_code='ONE'",tenant);site2=id("SELECT id FROM sites WHERE tenant_id=? AND site_code='TWO'",tenant);otherSite=id("SELECT id FROM sites WHERE tenant_id=?",other);
        db.update("INSERT INTO equipment(tenant_id,site_id,asset_code,name) VALUES(?,?,?,'A4 equipment'),(?,?,?,'A4 hidden')",tenant,site1,"a4team_e1_"+key,tenant,site2,"a4team_e2_"+key);
        equipmentId=id("SELECT id FROM equipment WHERE tenant_id=? AND site_id=?",tenant,site1);hiddenEquipment=id("SELECT id FROM equipment WHERE tenant_id=? AND site_id=?",tenant,site2);
        db.update("INSERT INTO tenant_member_site_scopes(tenant_id,membership_id,site_id,granted_by) VALUES(?,?,?,?)",tenant,member,site1,admin);
        actor(true);
    }
    private void actor(boolean isAdmin) {
        var company=new TenantEntity();company.setId(tenant);company.setEnabled(true);
        when(access.requireTenantAccess()).thenReturn(new TenantAccess(isAdmin?admin:user,company,isAdmin?adminMember:member,isAdmin?Set.of("TENANT_ADMIN"):Set.of()));
        when(access.requireEnabledUserId()).thenReturn(isAdmin?admin:user);
        when(access.requireActiveTenantId()).thenReturn(tenant);
    }
    @AfterEach void cleanup() {
        if(tenant>0) {
            db.update("DELETE FROM audit_events WHERE tenant_id=?",tenant);
            db.update("DELETE FROM work_order_reassignments WHERE tenant_id=?",tenant);
            db.update("DELETE FROM work_order_logs WHERE work_order_id IN (SELECT id FROM work_orders WHERE tenant_id=?)",tenant);
            db.update("DELETE FROM work_orders WHERE tenant_id=?",tenant);
            db.update("DELETE FROM team_operations WHERE tenant_id=?",tenant);
            db.update("DELETE FROM team_members WHERE tenant_id=?",tenant);
            db.update("DELETE FROM team_site_scopes WHERE tenant_id=?",tenant);
            db.update("DELETE FROM teams WHERE tenant_id IN (?,?)",tenant,other);
            db.update("DELETE FROM equipment WHERE tenant_id=?",tenant);
            db.update("DELETE FROM tenant_member_site_scopes WHERE tenant_id=?",tenant);
            db.update("DELETE FROM sites WHERE tenant_id IN (?,?)",tenant,other);
            db.update("DELETE FROM tenant_member_roles WHERE tenant_id=?",tenant);
            db.update("DELETE FROM tenant_memberships WHERE tenant_id=?",tenant);
            db.update("DELETE FROM tenants WHERE id IN (?,?)",tenant,other);
        }
        for(long id:new long[]{admin,user})if(id>0)db.update("DELETE FROM users WHERE id=?",id);
    }
    @Test void 创建替换与同号快照重放保存原版本并隔离企业() {
        String request=request();var created=service.create(new TeamCreateDto(request,"T1","初始团队",true));long team=Long.parseLong(created.id());
        service.update(team,new TeamUpdateDto(request(),0L,"后续团队",true));
        assertEquals(created,service.create(new TeamCreateDto(request," T1 "," 初始团队 ",true)));
        assertEquals(2,id("SELECT COUNT(*) FROM team_operations WHERE tenant_id=?",tenant));assertEquals(1,id("SELECT version FROM teams WHERE id=?",team));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.create(new TeamCreateDto(request,"T1","异内容",true))).getStatusCode().value());
        db.update("INSERT INTO teams(tenant_id,team_code,name,created_by) VALUES(?,'FOREIGN','其他企业团队',?)",other,admin);
        long foreign=id("SELECT id FROM teams WHERE tenant_id=?",other);
        assertEquals(1,service.list().size());assertEquals(404,assertThrows(ResponseStatusException.class,()->service.detail(foreign)).getStatusCode().value());
    }
    @Test void 普通人只读自己启用团队且团队厂区不扩大个人设备范围() {
        var team=service.create(new TeamCreateDto(request(),"T1","参加团队",true));long id=Long.parseLong(team.id());
        service.members(id,new TeamMembersDto(request(),0L,List.of(member)));
        service.sites(id,new TeamSitesDto(request(),1L,List.of(site1,site2)));
        var hidden=service.create(new TeamCreateDto(request(),"T2","未参加团队",true));
        actor(false);
        assertEquals(1,service.list().size());assertEquals(List.of(Long.toString(site1),Long.toString(site2)),service.detail(id).siteIds());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.detail(Long.parseLong(hidden.id()))).getStatusCode().value());
        assertNotNull(equipment.selectById(equipmentId));assertNull(equipment.selectById(hiddenEquipment));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.update(id,new TeamUpdateDto(request(),2L,"越权",true))).getStatusCode().value());
        assertEquals(1,count("DENIED"));
    }
    @Test void 团队未完成工单阻止停用移除负责人及缩厂区且完成后允许() {
        var team=service.create(new TeamCreateDto(request(),"T1","负责团队",true));long teamId=Long.parseLong(team.id());
        service.members(teamId,new TeamMembersDto(request(),0L,List.of(member)));service.sites(teamId,new TeamSitesDto(request(),1L,List.of(site1)));
        db.update("INSERT INTO work_orders(tenant_id,site_id,order_no,title,equipment_id,status,assignee_user_id,team_id) VALUES(?,?,?,'A4 team order',?,'IN_PROGRESS',?,?)",tenant,site1,"A4TEAM-"+key,equipmentId,user,teamId);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.update(teamId,new TeamUpdateDto(request(),2L,"负责团队",false))).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.members(teamId,new TeamMembersDto(request(),2L,List.of()))).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.sites(teamId,new TeamSitesDto(request(),2L,List.of()))).getStatusCode().value());
        assertEquals(2,id("SELECT version FROM teams WHERE id=?",teamId));
        db.update("UPDATE work_orders SET status='COMPLETED' WHERE tenant_id=?",tenant);
        assertFalse(service.update(teamId,new TeamUpdateDto(request(),2L,"负责团队",false)).enabled());
        assertTrue(service.members(teamId,new TeamMembersDto(request(),3L,List.of())).membershipIds().isEmpty());
        assertTrue(service.sites(teamId,new TeamSitesDto(request(),4L,List.of())).siteIds().isEmpty());
        assertEquals(user,id("SELECT assignee_user_id FROM work_orders WHERE tenant_id=?",tenant));
    }
    @Test void 成功审计失败回滚关系版本和幂等结果并独立记录失败() {
        var team=service.create(new TeamCreateDto(request(),"T1","审计回滚",true));long teamId=Long.parseLong(team.id());
        doThrow(new org.springframework.dao.DataIntegrityViolationException("A4 controlled team audit failure"))
                .when(auditMapper).insert(argThat((AuditEventEntity event)->event!=null && event.getTenantId()==tenant
                        && event.getAction()==AuditAction.TEAM_MEMBERS_CHANGED && event.getOutcome()==AuditOutcome.SUCCESS));
        assertThrows(RuntimeException.class,()->service.members(teamId,new TeamMembersDto(request(),0L,List.of(member))));
        assertEquals(0,id("SELECT COUNT(*) FROM team_members WHERE tenant_id=?",tenant));assertEquals(0,id("SELECT version FROM teams WHERE id=?",teamId));
        assertEquals(1,id("SELECT COUNT(*) FROM team_operations WHERE tenant_id=?",tenant));assertEquals(1,count("FAILURE"));
    }
    @Test void 锁后管理员角色已撤销不能使用旧身份快照() {
        var team=service.create(new TeamCreateDto(request(),"T1","权限测试",true));
        db.update("DELETE FROM tenant_member_roles WHERE tenant_id=? AND membership_id=?",tenant,adminMember);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.update(Long.parseLong(team.id()),new TeamUpdateDto(request(),0L,"撤权后更新",true))).getStatusCode().value());
        assertEquals(0,id("SELECT version FROM teams WHERE id=?",Long.parseLong(team.id())));assertEquals(1,count("DENIED"));
    }
    @Test void 同版本竞争只有一个提交且失败不保存请求快照() throws Exception {
        var team=service.create(new TeamCreateDto(request(),"T1","版本竞争",true));long teamId=Long.parseLong(team.id());
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try {
            Callable<Integer> a=()->updateAfterStart(teamId,"名称A",ready,start),b=()->updateAfterStart(teamId,"名称B",ready,start);
            var first=pool.submit(a);var second=pool.submit(b);assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            assertEquals(Set.of(200,409),Set.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));
            assertEquals(1,id("SELECT version FROM teams WHERE id=?",teamId));assertEquals(2,id("SELECT COUNT(*) FROM team_operations WHERE tenant_id=?",tenant));
        }finally{start.countDown();pool.shutdownNow();}
    }
    private int updateAfterStart(long team,String name,CountDownLatch ready,CountDownLatch start)throws InterruptedException {
        ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));
        try{service.update(team,new TeamUpdateDto(request(),0L,name,true));return 200;}catch(ResponseStatusException error){return error.getStatusCode().value();}
    }
    private long id(String sql,Object... args){return db.queryForObject(sql,Long.class,args);}
    private long count(String outcome){return id("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND object_type='TEAM' AND outcome=?",tenant,outcome);}
}
