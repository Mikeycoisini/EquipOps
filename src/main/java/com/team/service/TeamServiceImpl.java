package com.team.service;

import com.audit.service.AuditEventService;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.enums.AuditAction;
import com.equipops.domain.enums.AuditObjectType;
import com.team.dto.*;
import com.team.persistence.TeamStore;
import com.team.vo.TeamVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 团队事务服务：个人权限不扩张；企业锁、版本、业务关系、幂等结果和成功审计原子提交。 */
@Service @RequiredArgsConstructor
public class TeamServiceImpl implements TeamService {
    private final CurrentAccessService access;
    private final TeamStore store;
    private final AuditEventService audit;
    private final ObjectMapper json;
    private record Locked(TeamStore.Row row,TeamVO replay) {}

    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<TeamVO> list() {
        // 1. 每次读取可信企业、成员及角色，不使用客户端提供的企业或长期角色快照。
        var actor=access.requireTenantAccess();
        // 2. 普通人只读自己参加的启用团队，管理员读取当前企业全部团队。
        return store.list(actor.getTenant().getId(),actor.getMembershipId(),admin(actor)).stream().map(row->view(row,false)).toList();
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TeamVO detail(Long id) {
        // 1. 输入和企业归属先验证，再检查普通成员是否参加启用团队。
        var actor=access.requireTenantAccess();requireId(id);
        var row=store.find(actor.getTenant().getId(),id,false);
        if(row==null || !admin(actor) && (!row.enabled() || !store.members(row.tenantId(),id,false).contains(actor.getMembershipId())))
            throw error(HttpStatus.NOT_FOUND,"团队不存在");
        // 2. 行、成员和工作范围来自同一只读事务快照。
        return view(row,false);
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public TeamVO create(TeamCreateDto request) {
        // 1. 写操作仅管理员，参数在任何持久化锁之前完成规范化及容量验证。
        var actor=writeActor();
        if(request==null)throw error(HttpStatus.BAD_REQUEST,"团队参数不能为空");
        String requestId=requestId(request.getRequestId());
        String code=text(request.getTeamCode(),64,"团队编号");String name=text(request.getName(),160,"团队名称");
        boolean enabled=request.getEnabled()==null || request.getEnabled();
        String hash=hash(AuditAction.TEAM_CREATED,actor,null,null,List.of(code,name,enabled));
        // 2. 企业锁串行化幂等请求；锁后重新验证当前管理员。
        var locked=lock(actor,null,requestId,hash,AuditAction.TEAM_CREATED);
        if(locked.replay()!=null)return locked.replay();
        long tenant=actor.getTenant().getId();
        // 3. 只有服务器确认的企业及创建人可以进入INSERT，企业内编号由唯一键兜底。
        long id;
        try {id=store.insert(tenant,code,name,enabled,actor.getUserId());}
        catch(DuplicateKeyException duplicate){throw error(HttpStatus.CONFLICT,"当前企业团队编号已存在");}
        // 4. 新团队不自动建立成员或厂区关系，不扩大个人权限。
        var result=new TeamVO(Long.toString(id),Long.toString(tenant),code,name,enabled,0,List.of(),List.of());
        // 5. 成功审计只保存固定布尔值，不保存团队名称正文。
        success(actor,AuditAction.TEAM_CREATED,id,Map.of("enabled",enabled));
        // 6. 保存完整结果快照，后续同号重试返回当时结果而非后续团队状态。
        save(actor,id,requestId,hash,AuditAction.TEAM_CREATED,result);
        return result;
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public TeamVO update(Long id,TeamUpdateDto request) {
        // 1. 校验团队、UUID、版本和名称，团队编号保持不可修改。
        var actor=writeActor();requireId(id);
        if(request==null || request.getEnabled()==null)throw error(HttpStatus.BAD_REQUEST,"团队名称和启停状态不能为空");
        String requestId=requestId(request.getRequestId());long expected=version(request.getExpectedVersion());
        String name=text(request.getName(),160,"团队名称");boolean enabled=request.getEnabled();
        String hash=hash(AuditAction.TEAM_UPDATED,actor,id,expected,List.of(name,enabled));
        // 2. 企业→团队→管理员锁后重验；幂等回放早于当前版本比较。
        var locked=lock(actor,id,requestId,hash,AuditAction.TEAM_UPDATED);
        if(locked.replay()!=null)return locked.replay();
        var row=locked.row();matchVersion(row,expected);
        // 3. 仅比较允许编辑的配置，实际没有变化时不递增版本或重复成功审计。
        List<String> fields=new ArrayList<>();if(!name.equals(row.name()))fields.add("name");if(enabled!=row.enabled())fields.add("enabled");
        // 4. 停用不能让团队未结束工单丢失资格，必须先显式交接。
        if(row.enabled() && !enabled && store.hasOpenOrders(row.tenantId(),id))throw error(HttpStatus.CONFLICT,"团队仍有未结束工单，请先完成责任交接");
        if(!fields.isEmpty()) {
            // 5. 条件更新仅能影响一行，原版本变化则全部回滚。
            bump(row,name,enabled);
            success(actor,AuditAction.TEAM_UPDATED,id,Map.of("changedFields",List.copyOf(fields),"beforeEnabled",row.enabled(),"afterEnabled",enabled));
            row=new TeamStore.Row(id,row.tenantId(),row.teamCode(),name,enabled,row.version()+1);
        }
        // 6. 包括无变更操作也保存结果快照，避免同号异内容后续绕过。
        var result=view(row,true);save(actor,id,requestId,hash,AuditAction.TEAM_UPDATED,result);return result;
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public TeamVO members(Long id,TeamMembersDto request) {
        // 1. 集合完整替换，空数组清空；重复、负数或超过500项在取锁前拒绝。
        var actor=writeActor();requireId(id);
        if(request==null)throw error(HttpStatus.BAD_REQUEST,"团队成员参数不能为空");
        String requestId=requestId(request.getRequestId());long expected=version(request.getExpectedVersion());
        List<Long> requested=ids(request.getMembershipIds());
        String hash=hash(AuditAction.TEAM_MEMBERS_CHANGED,actor,id,expected,strings(requested));
        // 2. 统一写锁与幂等回放，过期版本不能覆盖其他请求。
        var locked=lock(actor,id,requestId,hash,AuditAction.TEAM_MEMBERS_CHANGED);
        if(locked.replay()!=null)return locked.replay();
        var row=locked.row();matchVersion(row,expected);
        // 3. 只允许本企业启用成员和启用账号加入团队；不修改个人厂区授权。
        if(!store.lockMembers(row.tenantId(),requested).equals(requested))throw error(HttpStatus.NOT_FOUND,"包含不存在、停用或不属于当前企业的成员");
        List<Long> before=store.members(row.tenantId(),id,true);
        if(!before.equals(requested)) {
            // 4. 删除资格前检查被移除成员承担的未结束团队工单，保留历史责任。
            List<Long> removed=before.stream().filter(member->!requested.contains(member)).toList();
            if(store.hasOpenOrdersForMembers(row.tenantId(),id,removed))throw error(HttpStatus.CONFLICT,"待移除成员仍承担团队未结束工单，请先交接");
            // 5. 版本和关系在同一事务更新，任何写入或审计失败均回滚。
            bump(row,row.name(),row.enabled());store.replaceMembers(row.tenantId(),id,before,requested);
            success(actor,AuditAction.TEAM_MEMBERS_CHANGED,id,Map.of("field","membershipIds","before",strings(before),"after",strings(requested),"version",row.version()+1));
            row=advanced(row);
        }
        // 6. 结果包含当前完整工作厂区及成员，保存不可变幂等快照。
        var result=view(row,true);save(actor,id,requestId,hash,AuditAction.TEAM_MEMBERS_CHANGED,result);return result;
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public TeamVO sites(Long id,TeamSitesDto request) {
        // 1. 工作范围是团队接单的额外条件，绝不回填或扩大成员个人授权。
        var actor=writeActor();requireId(id);
        if(request==null)throw error(HttpStatus.BAD_REQUEST,"团队厂区参数不能为空");
        String requestId=requestId(request.getRequestId());long expected=version(request.getExpectedVersion());
        List<Long> requested=ids(request.getSiteIds());
        String hash=hash(AuditAction.TEAM_SITE_SCOPES_CHANGED,actor,id,expected,strings(requested));
        // 2. 锁后管理员重验和当前版本校验与成员替换保持一致。
        var locked=lock(actor,id,requestId,hash,AuditAction.TEAM_SITE_SCOPES_CHANGED);
        if(locked.replay()!=null)return locked.replay();
        var row=locked.row();matchVersion(row,expected);
        // 3. 工作厂区必须全部属于当前企业且启用，当前读锁定避免校验后停用。
        if(!store.lockSites(row.tenantId(),requested).equals(requested))throw error(HttpStatus.NOT_FOUND,"包含不存在、停用或不属于当前企业的厂区");
        List<Long> before=store.sites(row.tenantId(),id,true);
        if(!before.equals(requested)) {
            // 4. 缩小范围前保留未结束工单资格，管理员需先完成显式交接。
            List<Long> removed=before.stream().filter(site->!requested.contains(site)).toList();
            if(store.hasOpenOrdersForSites(row.tenantId(),id,removed))throw error(HttpStatus.CONFLICT,"待移除厂区仍有团队未结束工单，请先交接");
            // 5. 完整替换、版本推进和成功审计原子提交。
            bump(row,row.name(),row.enabled());store.replaceSites(row.tenantId(),id,before,requested);
            success(actor,AuditAction.TEAM_SITE_SCOPES_CHANGED,id,Map.of("field","siteIds","before",strings(before),"after",strings(requested),"version",row.version()+1));
            row=advanced(row);
        }
        // 6. 同号重试始终回放本次结果，包括当时的版本及完整集合。
        var result=view(row,true);save(actor,id,requestId,hash,AuditAction.TEAM_SITE_SCOPES_CHANGED,result);return result;
    }
    private TenantAccess writeActor() {
        // 写入前建立可信主体，普通成员不能通过调用Service绕过管理员门槛。
        var actor=access.requireTenantAccess();if(!admin(actor))throw error(HttpStatus.FORBIDDEN,"只有企业管理员可以维护团队");return actor;
    }
    private boolean admin(TenantAccess actor){return actor.getRoleCodes().contains("TENANT_ADMIN");}
    private Locked lock(TenantAccess actor,Long id,String request,String hash,AuditAction action) {
        // 1. 企业行是跨团队幂等请求和角色变更的共同串行边界。
        long tenant=actor.getTenant().getId();store.lockTenant(tenant);
        var operation=store.operation(tenant,request);
        if(operation!=null) {
            // 2. 即使回放历史成功结果，也必须拥有当前有效管理员资格。
            requireLockedAdmin(actor);
            if(!hash.equals(operation.hash()) || !action.name().equals(operation.action()))throw error(HttpStatus.CONFLICT,"请求号已用于不同操作或内容");
            try {return new Locked(null,json.readValue(operation.resultJson(),TeamVO.class));}
            catch(JacksonException invalid){throw new IllegalStateException("团队幂等结果不可用",invalid);}
        }
        // 3. 无历史请求才锁当前团队；当前读避免旧RR版本污染变更前值。
        var row=id==null?null:store.find(tenant,id,true);
        if(id!=null && row==null)throw error(HttpStatus.NOT_FOUND,"团队不存在");
        // 4. 等待企业或团队锁后重新检查账号、成员和角色。
        requireLockedAdmin(actor);return new Locked(row,null);
    }
    private void requireLockedAdmin(TenantAccess actor) {
        // SQL锁定验证不能用当前事务已建立的普通快照代替。
        if(!store.lockAdmin(actor.getTenant().getId(),actor.getMembershipId(),actor.getUserId()))throw error(HttpStatus.FORBIDDEN,"当前企业管理员权限已失效");
    }
    private void bump(TeamStore.Row row,String name,boolean enabled) {
        // 版本保持JSON安全整数；到达上限时明确拒绝而非溢出或舍入。
        if(row.version()<0 || row.version()>=TeamInput.MAX_VERSION)throw error(HttpStatus.CONFLICT,"团队版本不可用或已达上限");
        if(store.update(row.tenantId(),row.id(),row.version(),name,enabled)!=1)throw error(HttpStatus.CONFLICT,"团队版本已变化，请刷新后重试");
    }
    private TeamStore.Row advanced(TeamStore.Row row){return new TeamStore.Row(row.id(),row.tenantId(),row.teamCode(),row.name(),row.enabled(),row.version()+1);}
    private TeamVO view(TeamStore.Row row,boolean lock) {
        // 完整快照始终包含字符串ID和已确认的安全版本。
        if(row.version()<0 || row.version()>TeamInput.MAX_VERSION)throw error(HttpStatus.CONFLICT,"团队版本不可用");
        return new TeamVO(Long.toString(row.id()),Long.toString(row.tenantId()),row.teamCode(),row.name(),row.enabled(),row.version(),strings(store.members(row.tenantId(),row.id(),lock)),strings(store.sites(row.tenantId(),row.id(),lock)));
    }
    private void success(TenantAccess actor,AuditAction action,long id,Map<String,Object> changes) {
        // 成功审计必须加入业务事务，不吞审计故障；名称正文只留在授权业务对象中。
        audit.appendSuccess(new AuditAppendCommand(actor.getTenant().getId(),actor.getUserId(),action,AuditObjectType.TEAM,id,changes,null,null));
    }
    private void save(TenantAccess actor,long id,String request,String hash,AuditAction action,TeamVO result) {
        // 保存结果而不是后续查询引用，避免重试返回其他请求的新版本。
        store.saveOperation(actor.getTenant().getId(),id,request,hash,action.name(),json.writeValueAsString(result));
    }
    private String hash(AuditAction action,TenantAccess actor,Long id,Long version,Object payload) {
        // 规范化固定顺序字段，跨进程重启也能生成同一SHA256；绑定可信操作者避免串用请求号。
        var canonical=new LinkedHashMap<String,Object>();canonical.put("action",action.name());canonical.put("actorUserId",actor.getUserId().toString());
        canonical.put("teamId",id==null?null:id.toString());canonical.put("expectedVersion",version);canonical.put("payload",payload);
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException("团队操作摘要算法不可用",unavailable);}
    }
    private void matchVersion(TeamStore.Row row,long expected){if(row.version()!=expected)throw error(HttpStatus.CONFLICT,"团队版本已变化，请刷新后重试");}
    private void requireId(Long id){if(id==null || id<=0)throw error(HttpStatus.BAD_REQUEST,"团队ID无效");}
    private long version(Long version){if(version==null || version<0 || version>TeamInput.MAX_VERSION)throw error(HttpStatus.BAD_REQUEST,"团队版本必须是安全非负整数");return version;}
    private String requestId(String value){
        // UUID字符串采用规范长度和格式，大小写统一后再做幂等比较。
        if(value==null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw error(HttpStatus.BAD_REQUEST,"requestId必须是UUID");
        return UUID.fromString(value).toString();
    }
    private String text(String value,int max,String label){if(value==null || value.trim().isEmpty() || value.trim().length()>max)throw error(HttpStatus.BAD_REQUEST,label+"不能为空或超长");return value.trim();}
    private List<Long> ids(List<Long> values){
        // 完整集合最多500项，重复ID不能静默合并以免掩盖客户端错误。
        if(values==null || values.size()>500)throw error(HttpStatus.BAD_REQUEST,"团队范围集合不能为空或超过500项");
        var sorted=new TreeSet<Long>();for(Long value:values)if(value==null || value<=0 || !sorted.add(value))throw error(HttpStatus.BAD_REQUEST,"集合必须包含唯一正整数ID");return List.copyOf(sorted);
    }
    private List<String> strings(List<Long> ids){return ids.stream().map(String::valueOf).toList();}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}
