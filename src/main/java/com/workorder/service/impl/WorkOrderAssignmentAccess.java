package com.workorder.service.impl;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 派单及改派共用资格验证；团队不能替代个人厂区授权。 */
@Service @RequiredArgsConstructor
public class WorkOrderAssignmentAccess {
 private final CurrentAccessService access;
 private final JdbcTemplate jdbc;
 /** 1.验证管理员；2.锁企业；3.当前读重验账号、成员及角色。 */
 @Transactional(propagation=Propagation.MANDATORY)
 public TenantAccess lockAdmin() {
  access.requireTenantAdminTenantId(); // 服务调用也必须具有管理员权限。
  var actor=access.requireTenantAccess(); // 仅使用可信企业及用户。
  long tenant=actor.getTenant().getId(); // 固定本次企业。
  if(jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND enabled=1 FOR UPDATE",Long.class,tenant).isEmpty())
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前企业不可用"); // 与团队及范围变更串行。
  var admins=jdbc.queryForList("SELECT m.id FROM tenant_memberships m JOIN users u ON u.id=m.user_id "
    +"JOIN tenant_member_roles mr ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.id JOIN roles r ON r.id=mr.role_id "
    +"WHERE m.tenant_id=? AND m.user_id=? AND m.enabled=1 AND u.enabled=1 AND r.role_code='TENANT_ADMIN' FOR UPDATE",
    Long.class,tenant,actor.getUserId()); // 锁等待后不得沿用旧管理员快照。
  if(admins.isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"管理员权限已失效"); // 失败关闭写入。
  return actor; // 返回本次可信身份，不能缓存跨请求使用。
 }
 /** 1.锁定启用厂区和目标成员；2.个人授权；3.可选团队资格交集。 */
 @Transactional(propagation=Propagation.MANDATORY)
 public void requireTarget(Long tenant,Long user,Long site,Long team) {
  if(site==null || user==null || user<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"负责人或厂区无效"); // 防止缺失归属继续写入。
  if(jdbc.queryForList("SELECT id FROM sites WHERE tenant_id=? AND id=? AND enabled=1 FOR UPDATE",Long.class,tenant,site).isEmpty())
   throw new ResponseStatusException(HttpStatus.NOT_FOUND,"工单厂区不存在或未启用"); // 管理员也不能分配停用厂区。
  var members=jdbc.queryForList("SELECT m.id FROM tenant_memberships m JOIN users u ON u.id=m.user_id "
    +"WHERE m.tenant_id=? AND m.user_id=? AND m.enabled=1 AND u.enabled=1 FOR UPDATE",Long.class,tenant,user); // 锁定有效目标。
  if(members.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"负责人必须是当前企业有效成员"); // 隐藏其他企业成员资料。
  long member=members.get(0); // 唯一企业成员关系。
  var roles=jdbc.queryForList("SELECT r.role_code FROM tenant_member_roles mr JOIN roles r ON r.id=mr.role_id WHERE mr.tenant_id=? AND mr.membership_id=? FOR UPDATE",String.class,tenant,member); // 当前读判断管理员例外。
  if(!roles.contains("TENANT_ADMIN") && jdbc.queryForList("SELECT site_id FROM tenant_member_site_scopes WHERE tenant_id=? AND membership_id=? AND site_id=? FOR UPDATE",Long.class,tenant,member,site).isEmpty())
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"负责人没有该厂区个人授权"); // 团队成员身份不能扩大个人范围。
  if(team!=null) { // 个人接单不要求额外团队，团队接单则必须同时满足以下条件。
   if(jdbc.queryForList("SELECT id FROM teams WHERE tenant_id=? AND id=? AND enabled=1 FOR UPDATE",Long.class,tenant,team).isEmpty())
    throw new ResponseStatusException(HttpStatus.NOT_FOUND,"团队不存在或未启用"); // 同企业团队当前读。
   if(jdbc.queryForList("SELECT membership_id FROM team_members WHERE tenant_id=? AND team_id=? AND membership_id=? FOR UPDATE",Long.class,tenant,team,member).isEmpty()
     ||jdbc.queryForList("SELECT site_id FROM team_site_scopes WHERE tenant_id=? AND team_id=? AND site_id=? FOR UPDATE",Long.class,tenant,team,site).isEmpty())
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"负责人及厂区不满足团队资格交集"); // 两项缺一不可。
  }
 }
}
