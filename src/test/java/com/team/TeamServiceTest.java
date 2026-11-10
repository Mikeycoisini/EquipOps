package com.team;

import com.audit.service.AuditEventService;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.entity.TenantEntity;
import com.team.dto.*;
import com.team.persistence.TeamStore;
import com.team.service.TeamServiceImpl;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 团队行为测试：不扩大个人授权，变更保持版本、快照幂等与历史责任约束。 */
class TeamServiceTest {
    private final Memory store=new Memory();
    private final AuditEventService audit=mock(AuditEventService.class);
    private final CurrentAccessService access=mock(CurrentAccessService.class);
    private boolean admin=true;
    private TeamServiceImpl service;
    private String request(){return UUID.randomUUID().toString();}
    @BeforeEach void setup(){
        when(access.requireTenantAccess()).thenAnswer(call->{var tenant=new TenantEntity();tenant.setId(7L);tenant.setEnabled(true);return new TenantAccess(19L,tenant,admin?1L:21L,admin?Set.of("TENANT_ADMIN"):Set.of());});
        service=new TeamServiceImpl(access,store,audit,new ObjectMapper());
    }
    @Test void 管理员创建完整快照并追加成功审计(){
        var result=service.create(new TeamCreateDto(request()," NEW "," 新团队 ",null));
        assertEquals("101",result.id());assertEquals("7",result.tenantId());assertEquals("NEW",result.teamCode());assertEquals("新团队",result.name());
        assertTrue(result.enabled());assertEquals(0,result.version());assertTrue(result.membershipIds().isEmpty());assertEquals(1,store.operations.size());
        verify(audit).appendSuccess(argThat(event->event.getObjectId()==101L && event.getChanges().equals(Map.of("enabled",true))));
    }
    @Test void 同号同规范载荷返回原快照不被后续团队变化污染(){
        String request=request();var first=service.create(new TeamCreateDto(request,"NEW","团队",true));
        store.rows.put(101L,new TeamStore.Row(101,7,"NEW","后续改名",false,9));
        var replay=service.create(new TeamCreateDto(request," NEW "," 团队 ",true));
        assertEquals(first,replay);assertEquals(1,store.inserts);verify(audit,times(1)).appendSuccess(any());
    }
    @Test void 同请求号不同动作或对象或内容返回409(){
        String request=request();service.create(new TeamCreateDto(request,"NEW","团队",true));
        status(409,()->service.create(new TeamCreateDto(request,"NEW","另一个团队",true)));
        status(409,()->service.members(100L,new TeamMembersDto(request,0L,List.of(21L))));
        assertEquals(1,store.inserts);
    }
    @Test void 成员完整替换稳定排序并只递增一次版本(){
        var result=service.members(100L,new TeamMembersDto(request(),0L,List.of(22L,21L)));
        assertEquals(List.of("21","22"),result.membershipIds());assertEquals(1,result.version());
        verify(audit).appendSuccess(argThat(event->event.getChanges().get("field").equals("membershipIds")
                && event.getChanges().get("before").equals(List.of("21")) && event.getChanges().get("after").equals(List.of("21","22"))));
    }
    @Test void 成员重放返回当时版本即使团队后续已变化(){
        String request=request();var result=service.members(100L,new TeamMembersDto(request,0L,List.of(22L)));
        service.update(100L,new TeamUpdateDto(request(),1L,"改名",true));
        assertEquals(result,service.members(100L,new TeamMembersDto(request,0L,List.of(22L))));
        assertEquals(2,store.rows.get(100L).version());
    }
    @Test void 空数组完整撤清成员和工作厂区(){
        assertTrue(service.members(100L,new TeamMembersDto(request(),0L,List.of())).membershipIds().isEmpty());
        assertTrue(service.sites(100L,new TeamSitesDto(request(),1L,List.of())).siteIds().isEmpty());
        assertEquals(2,store.rows.get(100L).version());
    }
    @Test void 相同集合不递增不成功审计但保存幂等快照(){
        var result=service.members(100L,new TeamMembersDto(request(),0L,List.of(21L)));
        assertEquals(0,result.version());assertEquals(0,store.updates);assertEquals(1,store.operations.size());verifyNoInteractions(audit);
    }
    @Test void 旧版本即使集合相同仍冲突(){
        store.rows.put(100L,new TeamStore.Row(100,7,"T1","团队一",true,2));
        status(409,()->service.members(100L,new TeamMembersDto(request(),1L,List.of(21L))));
        assertEquals(0,store.updates);assertTrue(store.operations.isEmpty());
    }
    @Test void 待交接团队不可停用(){
        store.open=true;
        status(409,()->service.update(100L,new TeamUpdateDto(request(),0L,"团队一",false)));
        assertTrue(store.rows.get(100L).enabled());assertEquals(0,store.updates);
    }
    @Test void 未完成工单负责人不可从团队移除(){
        store.openMember=true;
        status(409,()->service.members(100L,new TeamMembersDto(request(),0L,List.of(22L))));
        assertEquals(List.of(21L),store.memberIds.get(100L));assertEquals(0,store.updates);
    }
    @Test void 未完成工单厂区不可从团队工作范围移除(){
        store.openSite=true;
        status(409,()->service.sites(100L,new TeamSitesDto(request(),0L,List.of(12L))));
        assertEquals(List.of(11L),store.siteIds.get(100L));assertEquals(0,store.updates);
    }
    @Test void 外企业停用成员或停用厂区全部拒绝(){
        status(404,()->service.members(100L,new TeamMembersDto(request(),0L,List.of(99L))));
        status(404,()->service.sites(100L,new TeamSitesDto(request(),0L,List.of(99L))));
        assertEquals(0,store.updates);
    }
    @Test void 普通成员不能写且管理员锁后撤权同样拒绝(){
        admin=false;status(403,()->service.update(100L,new TeamUpdateDto(request(),0L,"名称",true)));
        assertTrue(store.locks.isEmpty());
        admin=true;store.admin=false;status(403,()->service.update(100L,new TeamUpdateDto(request(),0L,"名称",true)));
        assertEquals("tenant",store.locks.get(0));assertEquals("team",store.locks.get(1));assertEquals(0,store.updates);
    }
    @Test void 普通人只读自己参加的启用团队(){
        store.rows.put(101L,new TeamStore.Row(101,7,"OTHER","其他团队",true,0));store.memberIds.put(101L,List.of(22L));store.siteIds.put(101L,List.of(12L));
        admin=false;assertEquals(1,service.list().size());assertEquals("100",service.detail(100L).id());status(404,()->service.detail(101L));
        store.rows.put(100L,new TeamStore.Row(100,7,"T1","团队一",false,0));status(404,()->service.detail(100L));assertTrue(service.list().isEmpty());
        admin=true;assertEquals(2,service.list().size());
    }
    @Test void 重复负数空元素超容量以及小数版本在写前拒绝(){
        for(var ids:List.of(List.of(21L,21L),List.of(-1L),Arrays.asList(21L,null),Collections.nCopies(501,21L)))
            status(400,()->service.members(100L,new TeamMembersDto(request(),0L,ids)));
        status(400,()->service.sites(100L,new TeamSitesDto(request(),-1L,List.of())));
        status(400,()->service.create(new TeamCreateDto("not-uuid","T","名字",true)));
        assertTrue(store.locks.isEmpty());
    }
    @Test void 审计失败继续抛出触发事务回滚(){
        doThrow(new IllegalStateException("受控审计故障")).when(audit).appendSuccess(any());
        assertThrows(IllegalStateException.class,()->service.members(100L,new TeamMembersDto(request(),0L,List.of(22L))));
    }
    private void status(int code,Runnable call){assertEquals(code,assertThrows(ResponseStatusException.class,call::run).getStatusCode().value());}
    static class Memory implements TeamStore {
        final Map<Long,Row> rows=new LinkedHashMap<>();final Map<Long,List<Long>> memberIds=new HashMap<>(),siteIds=new HashMap<>();
        final Map<String,Operation> operations=new HashMap<>();final List<String> locks=new ArrayList<>();
        boolean admin=true,open,openMember,openSite;int inserts,updates;
        Memory(){rows.put(100L,new Row(100,7,"T1","团队一",true,0));memberIds.put(100L,List.of(21L));siteIds.put(100L,List.of(11L));}
        public void lockTenant(long t){assertEquals(7,t);locks.add("tenant");}
        public boolean lockAdmin(long t,long m,long u){assertEquals(7,t);locks.add("admin");return admin;}
        public Row find(long t,long id,boolean lock){assertEquals(7,t);if(lock)locks.add("team");return rows.get(id);}
        public List<Row> list(long t,long m,boolean admin){assertEquals(7,t);return rows.values().stream().filter(row->admin||row.enabled()&&memberIds.get(row.id()).contains(m)).toList();}
        public List<Long> members(long t,long id,boolean lock){return memberIds.get(id);}
        public List<Long> sites(long t,long id,boolean lock){return siteIds.get(id);}
        public Operation operation(long t,String id){return operations.get(id);}
        public List<Long> lockMembers(long t,List<Long> ids){return ids.stream().filter(id->List.of(21L,22L).contains(id)).toList();}
        public List<Long> lockSites(long t,List<Long> ids){return ids.stream().filter(id->List.of(11L,12L).contains(id)).toList();}
        public boolean hasOpenOrders(long t,long id){return open;}
        public boolean hasOpenOrdersForMembers(long t,long id,List<Long> removed){return !removed.isEmpty()&&openMember;}
        public boolean hasOpenOrdersForSites(long t,long id,List<Long> removed){return !removed.isEmpty()&&openSite;}
        public long insert(long t,String code,String name,boolean enabled,long actor){inserts++;rows.put(101L,new Row(101,t,code,name,enabled,0));memberIds.put(101L,List.of());siteIds.put(101L,List.of());return 101;}
        public int update(long t,long id,long version,String name,boolean enabled){var row=rows.get(id);if(row.version()!=version)return 0;updates++;rows.put(id,new Row(id,t,row.teamCode(),name,enabled,version+1));return 1;}
        public void replaceMembers(long t,long id,List<Long> before,List<Long> after){assertEquals(before,memberIds.get(id));memberIds.put(id,after);}
        public void replaceSites(long t,long id,List<Long> before,List<Long> after){assertEquals(before,siteIds.get(id));siteIds.put(id,after);}
        public void saveOperation(long t,long id,String request,String hash,String action,String json){operations.put(request,new Operation(hash,action,json));}
    }
}
