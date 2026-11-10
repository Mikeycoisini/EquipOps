package com.support;

import com.EquipOpsApplication;
import com.audit.mapper.AuditEventMapper;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.tenant.SiteScopeUpdateDto;
import com.equipops.domain.entity.AuditEventEntity;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.enums.AuditOutcome;
import com.tenant.service.SiteScopeService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** 真实隔离MySQL验证范围、版本与审计同事务以及并发版本冲突，清理仅针对UUID夹具。 */
@SpringBootTest(classes=EquipOpsApplication.class)
@EnabledIfEnvironmentVariable(named="A3_MYSQL_TESTS",matches="true")
class SiteScopeTransactionIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired SiteScopeService service;
    @Autowired com.tenant.service.SiteService siteService;
    @MockitoBean CurrentAccessService access;
    @MockitoSpyBean AuditEventMapper auditMapper;
    long tenant,admin,user,member,adminMember,site1,site2,role;

    @BeforeEach void fixture() {
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).matches("equipops_a1_[A-Za-z0-9_]+"));
        String key=UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO roles(role_code,name) SELECT 'TENANT_ADMIN','企业管理员' WHERE NOT EXISTS(SELECT 1 FROM roles WHERE role_code='TENANT_ADMIN')");
        role=id("SELECT id FROM roles WHERE role_code='TENANT_ADMIN'");
        db.update("INSERT INTO users(account,password_hash,display_name) VALUES(?,'unused-test-hash','A3 scope admin'),(?,'unused-test-hash','A3 scope member')","a3scope_admin_"+key,"a3scope_user_"+key);
        admin=id("SELECT id FROM users WHERE account=?","a3scope_admin_"+key);
        user=id("SELECT id FROM users WHERE account=?","a3scope_user_"+key);
        db.update("INSERT INTO tenants(tenant_code,name) VALUES(?,'A3 scope transaction')","a3scope_"+key);
        tenant=id("SELECT id FROM tenants WHERE tenant_code=?","a3scope_"+key);
        db.update("INSERT INTO tenant_memberships(tenant_id,user_id) VALUES(?,?),(?,?)",tenant,admin,tenant,user);
        adminMember=id("SELECT id FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,admin);
        member=id("SELECT id FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,user);
        db.update("INSERT INTO tenant_member_roles(tenant_id,membership_id,role_id) VALUES(?,?,?)",tenant,adminMember,role);
        db.update("INSERT INTO sites(tenant_id,site_code,name,is_default) VALUES(?,'SCOPE1','A3 scope one',1),(?,'SCOPE2','A3 scope two',0)",tenant,tenant);
        site1=id("SELECT id FROM sites WHERE tenant_id=? AND site_code='SCOPE1'",tenant);
        site2=id("SELECT id FROM sites WHERE tenant_id=? AND site_code='SCOPE2'",tenant);
        var company=new TenantEntity();company.setId(tenant);company.setEnabled(true);
        when(access.requireTenantAccess()).thenReturn(new TenantAccess(admin,company,adminMember,Set.of("TENANT_ADMIN")));
        when(access.requireEnabledUserId()).thenReturn(admin);
        when(access.requireTenantAdminTenantId()).thenReturn(tenant);
        when(access.requireActiveTenantId()).thenReturn(tenant);
    }
    @AfterEach void cleanup() {
        if(tenant>0) {
            db.update("DELETE FROM audit_events WHERE tenant_id=?",tenant);
            db.update("DELETE FROM tenant_member_site_scopes WHERE tenant_id=?",tenant);
            db.update("DELETE FROM sites WHERE tenant_id=?",tenant);
            db.update("DELETE FROM tenant_member_roles WHERE tenant_id=?",tenant);
            db.update("DELETE FROM tenant_memberships WHERE tenant_id=?",tenant);
            db.update("DELETE FROM tenants WHERE id=?",tenant);
        }
        for(long userId:new long[]{admin,user})if(userId>0)db.update("DELETE FROM users WHERE id=?",userId);
    }
    @Test void 范围替换版本和审计原子提交且相同集合不重复写审计() {
        var request=new SiteScopeUpdateDto(0L,List.of(site1,site2));
        var result=service.updateScopes(member,request);
        assertEquals(1L,result.version());assertEquals(2,scopes());assertEquals(1,count("SUCCESS"));
        result=service.updateScopes(member,new SiteScopeUpdateDto(1L,List.of(site2,site1)));
        assertEquals(1L,result.version());assertEquals(1,count("SUCCESS"));
        result=service.updateScopes(member,new SiteScopeUpdateDto(1L,List.of()));
        assertEquals(2L,result.version());assertEquals(0,scopes());assertEquals(2,count("SUCCESS"));
    }
    @Test void 成功审计插入失败回滚范围与版本且独立记录失败() {
        doThrow(new org.springframework.dao.DataIntegrityViolationException("A3 controlled failure"))
                .when(auditMapper).insert(argThat((AuditEventEntity event)->event!=null && event.getTenantId()==tenant && event.getOutcome()==AuditOutcome.SUCCESS));
        assertThrows(RuntimeException.class,()->service.updateScopes(member,new SiteScopeUpdateDto(0L,List.of(site1))));
        assertEquals(0,scopes());assertEquals(0,version());assertEquals(0,count("SUCCESS"));assertEquals(1,count("FAILURE"));
    }
    @Test void 锁后真实管理员角色撤销不能被旧身份快照绕过() {
        db.update("DELETE FROM tenant_member_roles WHERE tenant_id=? AND membership_id=?",tenant,adminMember);
        var failure=assertThrows(ResponseStatusException.class,()->service.updateScopes(member,new SiteScopeUpdateDto(0L,List.of(site1))));
        assertEquals(403,failure.getStatusCode().value());assertEquals(0,scopes());assertEquals(0,version());assertEquals(1,count("DENIED"));
    }
    @Test void 同版本并发替换只有一个成功() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try {
            Callable<Integer> first=()->replaceAfterStart(site1,ready,start);
            Callable<Integer> second=()->replaceAfterStart(site2,ready,start);
            Future<Integer> a=pool.submit(first);Future<Integer> b=pool.submit(second);
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            assertEquals(Set.of(200,409),Set.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));
            assertEquals(1,version());assertEquals(1,scopes());assertEquals(1,count("SUCCESS"));assertEquals(1,count("FAILURE"));
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void 厂区创建修改与无变更重试有准确成功审计() {
        var created=siteService.createSite(new com.equipops.domain.dto.tenant.SiteUpsertDto("AUDITED","private-name","private-address",true));
        assertNotNull(created.getId());assertEquals(1,siteEvents("SITE_CREATED","SUCCESS"));
        var request=new com.equipops.domain.dto.tenant.SiteUpsertDto("AUDITED","private-renamed","private-new-address",true);
        siteService.updateSite(created.getId(),request);
        assertEquals("private-renamed",db.queryForObject("SELECT name FROM sites WHERE id=?",String.class,created.getId()));
        assertEquals(1,siteEvents("SITE_UPDATED","SUCCESS"));
        siteService.updateSite(created.getId(),request);assertEquals(1,siteEvents("SITE_UPDATED","SUCCESS"));
        var summaries=db.queryForList("SELECT changes_json FROM audit_events WHERE tenant_id=? AND object_type='SITE'",String.class,tenant);
        assertTrue(summaries.stream().noneMatch(value->value.contains("private-")));
    }
    @Test void 厂区创建成功审计失败会回滚新厂区并独立记录失败() {
        doThrow(new org.springframework.dao.DataIntegrityViolationException("A3 controlled site audit failure"))
                .when(auditMapper).insert(argThat((AuditEventEntity event)->event!=null && event.getTenantId()==tenant
                        && event.getAction()==com.equipops.domain.enums.AuditAction.SITE_CREATED && event.getOutcome()==AuditOutcome.SUCCESS));
        assertThrows(RuntimeException.class,()->siteService.createSite(new com.equipops.domain.dto.tenant.SiteUpsertDto("ROLLBACK","private-name",null,true)));
        assertEquals(0,id("SELECT COUNT(*) FROM sites WHERE tenant_id=? AND site_code='ROLLBACK'",tenant));
        assertEquals(0,siteEvents("SITE_CREATED","SUCCESS"));assertEquals(1,siteEvents("SITE_CREATED","FAILURE"));
    }
    @Test void 厂区修改成功审计失败会回滚名称并独立记录失败() {
        doThrow(new org.springframework.dao.DataIntegrityViolationException("A3 controlled site audit failure"))
                .when(auditMapper).insert(argThat((AuditEventEntity event)->event!=null && event.getTenantId()==tenant
                        && event.getAction()==com.equipops.domain.enums.AuditAction.SITE_UPDATED && event.getOutcome()==AuditOutcome.SUCCESS));
        assertThrows(RuntimeException.class,()->siteService.updateSite(site2,new com.equipops.domain.dto.tenant.SiteUpsertDto("SCOPE2","private-rename",null,true)));
        assertEquals("A3 scope two",db.queryForObject("SELECT name FROM sites WHERE id=?",String.class,site2));
        assertEquals(0,siteEvents("SITE_UPDATED","SUCCESS"));assertEquals(1,siteEvents("SITE_UPDATED","FAILURE"));
    }
    private long siteEvents(String action,String outcome) {
        return id("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action=? AND outcome=?",tenant,action,outcome);
    }
    private int replaceAfterStart(long site,CountDownLatch ready,CountDownLatch start) throws InterruptedException {
        ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));
        try {service.updateScopes(member,new SiteScopeUpdateDto(0L,List.of(site)));return 200;}
        catch(ResponseStatusException error) {return error.getStatusCode().value();}
    }
    private long id(String sql,Object... args) {return db.queryForObject(sql,Long.class,args);}
    private long version() {return id("SELECT site_scope_version FROM tenant_memberships WHERE id=?",member);}
    private long scopes() {return id("SELECT COUNT(*) FROM tenant_member_site_scopes WHERE tenant_id=? AND membership_id=?",tenant,member);}
    private long count(String outcome) {return id("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='MEMBER_SITE_SCOPES_CHANGED' AND outcome=?",tenant,outcome);}
}
