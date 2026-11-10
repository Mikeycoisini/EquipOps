package com.tenant.service.impl;

import com.audit.service.AuditEventService;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.dto.tenant.SiteScopeUpdateDto;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 验证范围替换的授权、版本、无变更及审计；SQL存储桩不代替真实事务验证。 */
class SiteScopeServiceTest {
    private final Store store = new Store();
    private boolean denyAdmin;
    private int audits;
    private boolean auditFailure;
    private AuditAppendCommand event;
    private SiteScopeServiceImpl service() {
        var access = (CurrentAccessService) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CurrentAccessService.class},(p,m,a)-> {
            if (denyAdmin) throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"需要管理员");
            return m.getName().equals("requireEnabledUserId") ? 19L : 7L;
        });
        var audit = (AuditEventService) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AuditEventService.class},(p,m,a)-> {
            audits++; event=(AuditAppendCommand)a[0];
            if(auditFailure) throw new IllegalStateException("审计不可用");
            return null;
        });
        return new SiteScopeServiceImpl(access,store,audit);
    }
    @Test void 新成员查询为零授权且ID为字符串() {
        var result=service().getScopes(100L);
        assertEquals("100",result.membershipId()); assertEquals(0L,result.version()); assertTrue(result.siteIds().isEmpty());
    }
    @Test void 完整替换排序去顺序影响并递增一次版本() {
        var result=service().updateScopes(100L,new SiteScopeUpdateDto(0L,List.of(12L,11L)));
        assertEquals(List.of("11","12"),result.siteIds()); assertEquals(1L,result.version());
        assertEquals(List.of(11L,12L),store.scope); assertEquals(1,audits);
        assertEquals(7L,event.getTenantId()); assertEquals(19L,event.getActorUserId()); assertEquals(100L,event.getObjectId());
        assertTrue(store.queries.get(0).contains("FROM tenants"));
        assertTrue(store.queries.get(1).contains("FROM tenant_memberships"));
    }
    @Test void 空集合撤销全部授权() {
        store.scope=new ArrayList<>(List.of(11L));
        var result=service().updateScopes(100L,new SiteScopeUpdateDto(0L,List.of()));
        assertTrue(result.siteIds().isEmpty()); assertEquals(1L,result.version()); assertEquals(1,audits);
    }
    @Test void 相同集合不写库不递增不审计() {
        store.scope=new ArrayList<>(List.of(11L,12L));
        var result=service().updateScopes(100L,new SiteScopeUpdateDto(0L,List.of(12L,11L)));
        assertEquals(0L,result.version()); assertEquals(0,store.writes); assertEquals(0,audits);
    }
    @Test void 旧版本冲突不写入() {
        store.version=2;
        status(409,()->service().updateScopes(100L,new SiteScopeUpdateDto(1L,List.of(11L))));
        assertEquals(0,store.writes);
    }
    @Test void 无效或其他企业厂区拒绝整个替换() {
        status(404,()->service().updateScopes(100L,new SiteScopeUpdateDto(0L,List.of(99L))));
        assertEquals(0,store.writes);
    }
    @Test void 锁等待后管理员权限已撤销仍拒绝() {
        store.adminEnabled=false;
        status(403,()->service().updateScopes(100L,new SiteScopeUpdateDto(0L,List.of(11L))));
        assertEquals(0,store.writes);
    }
    @Test void 目标成员必须属于当前企业且有效() {
        store.memberEnabled=false;
        status(404,()->service().updateScopes(100L,new SiteScopeUpdateDto(0L,List.of(11L))));
        assertEquals(0,store.writes);
    }
    @Test void 非管理员不可读取也不可替换() {
        denyAdmin=true;
        status(403,()->service().getScopes(100L));
        status(403,()->service().updateScopes(100L,new SiteScopeUpdateDto(0L,List.of())));
        assertTrue(store.queries.isEmpty());
    }
    @Test void 重复负数空元素或超容量必须在访问数据库前拒绝() {
        for(var ids:List.of(List.of(11L,11L),List.of(-1L),Collections.nCopies(501,11L),Arrays.asList(11L,null)))
            status(400,()->service().updateScopes(100L,new SiteScopeUpdateDto(0L,ids)));
        status(400,()->service().updateScopes(100L,new SiteScopeUpdateDto(-1L,List.of())));
        assertTrue(store.queries.isEmpty());
    }
    @Test void 审计错误向外传播以触发业务事务回滚() {
        auditFailure=true;
        assertThrows(IllegalStateException.class,()->service().updateScopes(100L,new SiteScopeUpdateDto(0L,List.of(11L))));
    }
    private void status(int expected,Runnable operation) { assertEquals(expected,assertThrows(ResponseStatusException.class,operation::run).getStatusCode().value()); }
    private static class Store extends JdbcTemplate {
        long version;
        boolean memberEnabled=true,adminEnabled=true;
        List<Long> scope=new ArrayList<>();
        List<String> queries=new ArrayList<>();
        int writes;
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
            queries.add(sql);
            if(sql.contains("FROM tenants")) return List.of(Map.of("enabled",true));
            if(sql.contains("site_scope_version")) return memberEnabled ? List.of(Map.of("site_scope_version",version,"enabled",true)) : List.of();
            throw new AssertionError(sql);
        }
        @Override @SuppressWarnings("unchecked") public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
            queries.add(sql);
            if(sql.contains("JOIN users")) return adminEnabled ? (List<T>)List.of(19L) : List.of();
            if(sql.contains("FROM tenant_member_site_scopes")) return (List<T>)new ArrayList<>(scope);
            if(sql.contains("FROM sites")) return (List<T>)Arrays.stream(args).skip(1).filter(v->List.of(11L,12L).contains(v)).toList();
            throw new AssertionError(sql);
        }
        @Override public int update(String sql,Object... args) {
            writes++;
            if(sql.startsWith("DELETE")) {int size=scope.size();scope.clear();return size;}
            if(sql.startsWith("INSERT")) {scope.add((Long)args[2]);return 1;}
            if(sql.startsWith("UPDATE tenant_memberships")) {version++;return 1;}
            throw new AssertionError(sql);
        }
    }
}
