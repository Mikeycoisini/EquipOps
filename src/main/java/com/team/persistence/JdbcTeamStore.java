package com.team.persistence;

import java.sql.Statement;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** 团队授权管理SQL显式限定可信企业，当前读和行锁不经过业务数据权限拦截器。 */
@Repository @RequiredArgsConstructor
public class JdbcTeamStore implements TeamStore {
    private final JdbcTemplate jdbc;
    private static final String COLUMNS="id,tenant_id,team_code,name,enabled,version";

    @Override public void lockTenant(long tenantId) {
        // 1. 企业行是团队写事务的第一把锁，串行化成员资格及请求号竞争。
        if(jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND enabled=1 FOR UPDATE",Long.class,tenantId).isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前企业不可用");
    }
    @Override public boolean lockAdmin(long tenantId,long membershipId,long userId) {
        // 1. 锁后重验当前账号、成员和管理员角色，不能信任等待锁前的RR快照。
        return !jdbc.queryForList("SELECT m.id FROM tenant_memberships m JOIN users u ON u.id=m.user_id "
                +"JOIN tenant_member_roles mr ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.id "
                +"JOIN roles r ON r.id=mr.role_id WHERE m.tenant_id=? AND m.id=? AND m.user_id=? "
                +"AND m.enabled=1 AND u.enabled=1 AND r.role_code='TENANT_ADMIN' FOR UPDATE",Long.class,tenantId,membershipId,userId).isEmpty();
    }
    @Override public Row find(long tenantId,long teamId,boolean lock) {
        // 1. 所有详情和锁定查询都先限定企业，跨企业对象视为不存在。
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM teams WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->new Row(rs.getLong("id"),rs.getLong("tenant_id"),rs.getString("team_code"),rs.getString("name"),rs.getBoolean("enabled"),rs.getLong("version")),tenantId,teamId);
        return rows.isEmpty()?null:rows.get(0);
    }
    @Override public List<Row> list(long tenantId,long membershipId,boolean admin) {
        // 1. 普通成员只能看到自己参加的启用团队；团队资格不转化为个人厂区授权。
        String visibility=admin?"":" AND t.enabled=1 AND EXISTS (SELECT 1 FROM team_members m WHERE m.tenant_id=t.tenant_id AND m.team_id=t.id AND m.membership_id=?)";
        Object[] args=admin?new Object[]{tenantId}:new Object[]{tenantId,membershipId};
        return jdbc.query("SELECT t.id,t.tenant_id,t.team_code,t.name,t.enabled,t.version FROM teams t WHERE t.tenant_id=?"+visibility+" ORDER BY t.id",
                (rs,n)->new Row(rs.getLong("id"),rs.getLong("tenant_id"),rs.getString("team_code"),rs.getString("name"),rs.getBoolean("enabled"),rs.getLong("version")),args);
    }
    @Override public List<Long> members(long tenantId,long teamId,boolean lock) {
        // 1. 完整集合读取保留历史资格记录，不按账号启停隐藏管理前值。
        return jdbc.queryForList("SELECT membership_id FROM team_members WHERE tenant_id=? AND team_id=? ORDER BY membership_id"+(lock?" FOR UPDATE":""),Long.class,tenantId,teamId);
    }
    @Override public List<Long> sites(long tenantId,long teamId,boolean lock) {
        // 1. 集合前值包括已停用厂区，管理员可明确移除而非静默丢失历史。
        return jdbc.queryForList("SELECT site_id FROM team_site_scopes WHERE tenant_id=? AND team_id=? ORDER BY site_id"+(lock?" FOR UPDATE":""),Long.class,tenantId,teamId);
    }
    @Override public Operation operation(long tenantId,String requestId) {
        // 1. 企业锁后当前读幂等结果，同号重试不能命中早期RR快照。
        var rows=jdbc.query("SELECT payload_hash,action,result_json FROM team_operations WHERE tenant_id=? AND request_id=? FOR UPDATE",
                (rs,n)->new Operation(rs.getString("payload_hash"),rs.getString("action"),rs.getString("result_json")),tenantId,requestId);
        return rows.isEmpty()?null:rows.get(0);
    }
    @Override public List<Long> lockMembers(long tenantId,List<Long> ids) {
        // 1. 空集合不生成无条件查询；仅允许启用账号及本企业启用成员。
        if(ids.isEmpty())return List.of();
        return jdbc.queryForList("SELECT m.id FROM tenant_memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? "
                +"AND m.id IN ("+marks(ids.size())+") AND m.enabled=1 AND u.enabled=1 ORDER BY m.id FOR UPDATE",Long.class,args(tenantId,ids));
    }
    @Override public List<Long> lockSites(long tenantId,List<Long> ids) {
        // 1. 工作厂区只能选择本企业启用厂区，锁定后保持到事务结束。
        if(ids.isEmpty())return List.of();
        return jdbc.queryForList("SELECT id FROM sites WHERE tenant_id=? AND id IN ("+marks(ids.size())+") AND enabled=1 ORDER BY id FOR UPDATE",Long.class,args(tenantId,ids));
    }
    @Override public boolean hasOpenOrders(long tenantId,long teamId) {
        // 1. 待验收也属于未结束工单，停用前必须先显式交接。
        return !jdbc.queryForList("SELECT id FROM work_orders WHERE tenant_id=? AND team_id=? AND status<>'COMPLETED' ORDER BY id FOR UPDATE",Long.class,tenantId,teamId).isEmpty();
    }
    @Override public boolean hasOpenOrdersForMembers(long tenantId,long teamId,List<Long> removed) {
        // 1. 只有被移除成员实际承担团队未结束工单时才阻止，不自动改派历史负责人。
        if(removed.isEmpty())return false;
        var args=new ArrayList<Object>();args.add(tenantId);args.add(teamId);args.addAll(removed);
        return !jdbc.queryForList("SELECT w.id FROM work_orders w JOIN tenant_memberships m ON m.tenant_id=w.tenant_id AND m.user_id=w.assignee_user_id "
                +"WHERE w.tenant_id=? AND w.team_id=? AND w.status<>'COMPLETED' AND m.id IN ("+marks(removed.size())+") ORDER BY w.id FOR UPDATE",Long.class,args.toArray()).isEmpty();
    }
    @Override public boolean hasOpenOrdersForSites(long tenantId,long teamId,List<Long> removed) {
        // 1. 缩小工作范围不能使团队现有未结束工单丢失厂区资格。
        if(removed.isEmpty())return false;
        var args=new ArrayList<Object>();args.add(tenantId);args.add(teamId);args.addAll(removed);
        return !jdbc.queryForList("SELECT id FROM work_orders WHERE tenant_id=? AND team_id=? AND status<>'COMPLETED' AND site_id IN ("
                +marks(removed.size())+") ORDER BY id FOR UPDATE",Long.class,args.toArray()).isEmpty();
    }
    @Override public long insert(long tenantId,String code,String name,boolean enabled,long actorId) {
        // 1. 创建人及企业仅由已验证的事务上下文提供，数据库生成主键。
        var key=new GeneratedKeyHolder();
        int rows=jdbc.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO teams(tenant_id,team_code,name,enabled,created_by) VALUES(?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1,tenantId);statement.setString(2,code);statement.setString(3,name);statement.setBoolean(4,enabled);statement.setLong(5,actorId);
            return statement;
        },key);
        // 2. 未取得唯一主键时失败，不能返回未落库对象。
        if(rows!=1 || key.getKey()==null)throw new IllegalStateException("团队创建失败");
        return key.getKey().longValue();
    }
    @Override public int update(long tenantId,long teamId,long version,String name,boolean enabled) {
        // 1. 团队配置、成员、范围的实际变更共用单一版本条件更新。
        return jdbc.update("UPDATE teams SET name=?,enabled=?,version=version+1 WHERE tenant_id=? AND id=? AND version=?",name,enabled,tenantId,teamId,version);
    }
    @Override public void replaceMembers(long tenantId,long teamId,List<Long> before,List<Long> after) {
        // 1. 完整替换只影响当前企业团队，删除计数与已锁前值必须一致。
        if(jdbc.update("DELETE FROM team_members WHERE tenant_id=? AND team_id=?",tenantId,teamId)!=before.size())throw new IllegalStateException("团队成员删除计数不一致");
        // 2. 每个新关系必须成功写入，任一失败由外层事务整体回滚。
        for(Long id:after)if(jdbc.update("INSERT INTO team_members(tenant_id,team_id,membership_id) VALUES(?,?,?)",tenantId,teamId,id)!=1)throw new IllegalStateException("团队成员保存失败");
    }
    @Override public void replaceSites(long tenantId,long teamId,List<Long> before,List<Long> after) {
        // 1. 工作厂区完整替换遵循同一企业边界与计数约束。
        if(jdbc.update("DELETE FROM team_site_scopes WHERE tenant_id=? AND team_id=?",tenantId,teamId)!=before.size())throw new IllegalStateException("团队厂区删除计数不一致");
        // 2. 此表不会写入个人厂区授权，团队范围只限制团队接单资格。
        for(Long id:after)if(jdbc.update("INSERT INTO team_site_scopes(tenant_id,team_id,site_id) VALUES(?,?,?)",tenantId,teamId,id)!=1)throw new IllegalStateException("团队厂区保存失败");
    }
    @Override public void saveOperation(long tenantId,long teamId,String requestId,String hash,String action,String resultJson) {
        // 1. 完整结果快照与业务版本、关系和成功审计共同提交，不能回放后续状态。
        if(jdbc.update("INSERT INTO team_operations(tenant_id,team_id,request_id,payload_hash,action,result_json) VALUES(?,?,?,?,?,?)",tenantId,teamId,requestId,hash,action,resultJson)!=1)
            throw new IllegalStateException("团队操作快照保存失败");
    }
    private String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
    private Object[] args(long tenant,List<Long> ids){var args=new ArrayList<Object>();args.add(tenant);args.addAll(ids);return args.toArray();}
}
