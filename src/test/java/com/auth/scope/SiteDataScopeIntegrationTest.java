package com.auth.scope;

import com.EquipOpsApplication;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipment.mapper.EquipmentMapper;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.entity.TenantEntity;
import com.tenant.mapper.SiteMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离MySQL验证真实插件、分页COUNT和实时撤权；每次Mapper调用使用独立事务快照。 */
@SpringBootTest(classes=EquipOpsApplication.class)
@EnabledIfEnvironmentVariable(named="A3_MYSQL_TESTS",matches="true")
class SiteDataScopeIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired EquipmentMapper equipment;
    @Autowired SiteMapper sites;
    @Autowired SiteWriteAccessService writes;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @MockitoBean CurrentAccessService access;
    long tenant,other,user,membership,site1,site2,e1,e2;
    @BeforeEach void fixture() {
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).matches("equipops_a1_[A-Za-z0-9_]+"));
        String key=UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO users(account,password_hash,display_name) VALUES(?,'unused','A3 SQL')","a3_"+key);
        user=id("SELECT id FROM users WHERE account=?","a3_"+key);
        db.update("INSERT INTO tenants(tenant_code,name) VALUES(?,'A3 SQL'),(?,'A3 other')","a3_"+key,"other_"+key);
        tenant=id("SELECT id FROM tenants WHERE tenant_code=?","a3_"+key);
        other=id("SELECT id FROM tenants WHERE tenant_code=?","other_"+key);
        db.update("INSERT INTO tenant_memberships(tenant_id,user_id) VALUES(?,?)",tenant,user);
        membership=id("SELECT id FROM tenant_memberships WHERE tenant_id=?",tenant);
        db.update("INSERT INTO sites(tenant_id,site_code,name,is_default) VALUES(?,'ONE','A3 one',1),(?,'TWO','A3 two',0),(?,'OTHER','A3 other',1)",tenant,tenant,other);
        site1=id("SELECT id FROM sites WHERE tenant_id=? AND site_code='ONE'",tenant);
        site2=id("SELECT id FROM sites WHERE tenant_id=? AND site_code='TWO'",tenant);
        long otherSite=id("SELECT id FROM sites WHERE tenant_id=?",other);
        db.update("INSERT INTO equipment(tenant_id,site_id,asset_code,name) VALUES(?,?,?,'A3 one'),(?,?,?,'A3 two'),(?,?,?,'A3 foreign')",tenant,site1,"a3e1_"+key,tenant,site2,"a3e2_"+key,other,otherSite,"a3e3_"+key);
        e1=id("SELECT id FROM equipment WHERE tenant_id=? AND site_id=?",tenant,site1);
        e2=id("SELECT id FROM equipment WHERE tenant_id=? AND site_id=?",tenant,site2);
        grant(site1);
        actor(false);
    }
    void actor(boolean admin) {
        var company=new TenantEntity();company.setId(tenant);company.setEnabled(true);
        when(access.requireTenantAccess()).thenReturn(new TenantAccess(user,company,membership,admin?Set.of("TENANT_ADMIN"):Set.of()));
    }
    void grant(long site) {db.update("INSERT INTO tenant_member_site_scopes(tenant_id,membership_id,site_id,granted_by) VALUES(?,?,?,?)",tenant,membership,site,user);}
    long id(String sql,Object... args){return db.queryForObject(sql,Long.class,args);}
    @AfterEach void cleanup() {
        if(tenant>0) {
            db.update("DELETE FROM equipment WHERE tenant_id IN (?,?)",tenant,other);
            db.update("DELETE FROM tenant_member_site_scopes WHERE tenant_id=?",tenant);
            db.update("DELETE FROM sites WHERE tenant_id IN (?,?)",tenant,other);
            db.update("DELETE FROM tenant_memberships WHERE tenant_id=?",tenant);
            db.update("DELETE FROM tenants WHERE id IN (?,?)",tenant,other);
        }
        if(user>0)db.update("DELETE FROM users WHERE id=?",user);
    }
    @Test void insertAuthorizationUsesCurrentReadAfterEarlierSnapshotAndRevocation() {
        transaction.execute(status -> {
            assertNotNull(equipment.selectById(e1));
            // 第二连接提交撤权；当前长事务已建立旧RR快照，写入校验必须使用锁定当前读。
            Thread revoke = new Thread(() -> db.update("DELETE FROM tenant_member_site_scopes WHERE membership_id=?",membership));
            revoke.start();
            try { revoke.join(5000); } catch (InterruptedException e) { throw new RuntimeException(e); }
            assertFalse(revoke.isAlive());
            var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->writes.lockSite(site1));
            assertEquals(403,error.getStatusCode().value());
            status.setRollbackOnly(); return null;
        });
    }
    @Test void writeSiteMustBeEnabledEvenForAdminAndRequiresActualTransaction() {
        actor(true);
        db.update("UPDATE sites SET enabled=0 WHERE id=?",site1);
        transaction.execute(status -> {
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->writes.lockSite(site1));
            status.setRollbackOnly();return null;
        });
    }
    @Test void ordinaryListDetailAndPaginationOnlyContainGrantedSite() {
        assertEquals(1,equipment.selectCount(null));
        assertNotNull(equipment.selectById(e1));assertNull(equipment.selectById(e2));
        var page=equipment.selectPage(new Page<>(1,1),new QueryWrapper<>());
        assertEquals(1,page.getTotal());assertEquals(e1,page.getRecords().get(0).getId());
        assertEquals(1,sites.selectCount(null));
    }
    @Test void administratorSeesAllCurrentTenantButNeverOtherTenant() {
        actor(true);assertEquals(2,equipment.selectCount(null));assertEquals(2,sites.selectCount(null));
    }
    @Test void hiddenUpdateAndDeleteAffectZeroRows() {
        assertEquals(0,equipment.update(null,new UpdateWrapper<EquipmentEntity>().eq("id",e2).set("name","forbidden")));
        assertEquals(0,equipment.deleteById(e2));
        assertEquals("A3 two",db.queryForObject("SELECT name FROM equipment WHERE id=?",String.class,e2));
    }
    @Test void existingActorLosesScopeOnNextQueryAndNewGrantApplies() {
        assertEquals(1,equipment.selectCount(null));
        db.update("DELETE FROM tenant_member_site_scopes WHERE membership_id=?",membership);
        assertEquals(0,equipment.selectCount(null));
        grant(site2);assertNull(equipment.selectById(e1));assertNotNull(equipment.selectById(e2));
    }
    @Test void disabledSiteAndMissingActorFailClosed() {
        db.update("UPDATE sites SET enabled=0 WHERE id=?",site1);assertEquals(0,equipment.selectCount(null));
        when(access.requireTenantAccess()).thenThrow(new IllegalStateException("no request"));
        assertEquals(0,equipment.selectCount(null));
    }
}
