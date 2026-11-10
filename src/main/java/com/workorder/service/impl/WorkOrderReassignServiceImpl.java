package com.workorder.service.impl;

import com.audit.service.AuditEventService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.dto.workorder.WorkOrderReassignDto;
import com.equipops.domain.enums.*;
import com.equipops.domain.vo.WorkOrderVO;
import com.workorder.service.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 受控改派事务：资格、版本、历史、成功审计与幂等快照共同提交。 */
@Service
public class WorkOrderReassignServiceImpl implements WorkOrderReassignService {
 @Autowired private WorkOrderAssignmentAccess assignment;
 @Autowired private JdbcTemplate jdbc;
 @Autowired private AuditEventService audit;
 @Autowired private WorkOrderService workOrders;
 @Autowired private ObjectMapper json;
 private static final long MAX_VERSION=9_007_199_254_740_991L;

 /** 1.校验；2.锁定管理员；3.幂等重放；4.锁工单；5.状态版本与交集；6.条件更新；7.历史审计及结果快照。 */
 @Override @Transactional(rollbackFor=Exception.class)
 public WorkOrderVO reassign(Long id,WorkOrderReassignDto request) {
  validate(id,request); // 非法输入不能到达SQL。
  var actor=assignment.lockAdmin(); // 企业锁串行团队和范围替换，并重验当前管理员。
  long tenant=actor.getTenant().getId(); // 企业及操作者不取自请求正文。
  String key=request.getRequestId().toLowerCase(Locale.ROOT); // UUID大小写统一，避免相同请求被当新操作。
  var payload=new LinkedHashMap<String,Object>(); // 使用固定顺序及规范化内容计算请求指纹。
  payload.put("actor",actor.getUserId());payload.put("id",id);payload.put("version",request.getExpectedVersion()); // 包含可信操作者与对象。
  payload.put("user",request.getTargetAssigneeUserId());payload.put("team",request.getTargetTeamId());payload.put("reason",request.getReason().trim()); // 同一原因去首尾空格幂等。
  String hash=hash(json.writeValueAsString(payload)); // 指纹不写日志，也不替代授权。
  var previous=jdbc.queryForList("SELECT payload_hash,result_json FROM work_order_reassignments WHERE tenant_id=? AND request_id=? FOR UPDATE",tenant,key); // 同企业请求号唯一。
  if(!previous.isEmpty()) {
   if(!hash.equals(previous.get(0).get("payload_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"请求号已用于其他改派内容"); // 同号异内容拒绝。
   return json.readValue((String)previous.get(0).get("result_json"),WorkOrderVO.class); // 回放当时完整结果，不返回后来另一改派结果。
  }
  var rows=jdbc.queryForList("SELECT site_id,status,assignee_user_id,team_id,version FROM work_orders WHERE tenant_id=? AND id=? FOR UPDATE",tenant,id); // 工单归属当前读与旧状态动作竞争。
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"工单不存在"); // 跨企业统一隐藏。
  var row=rows.get(0); // 只处理锁定的唯一工单。
  String status=(String)row.get("status"); // 不新造ASSIGNED状态。
  if(!Set.of("PENDING","IN_PROGRESS").contains(status))throw new ResponseStatusException(HttpStatus.CONFLICT,"当前状态不允许改派"); // 验收中及完成后禁止改派。
  long version=((Number)row.get("version")).longValue(); // 当前读版本，不能用请求覆盖。
  if(version!=request.getExpectedVersion() || version>=MAX_VERSION)throw new ResponseStatusException(HttpStatus.CONFLICT,"工单版本已变化，请刷新后重试"); // 竞争返回409。
  Long beforeUser=number(row.get("assignee_user_id")),beforeTeam=number(row.get("team_id")); // 保存旧责任，用于历史而非清除。
  Long site=number(row.get("site_id")); // 工单厂区继承设备，改派不能迁移厂区。
  assignment.requireTarget(tenant,request.getTargetAssigneeUserId(),site,request.getTargetTeamId()); // 必须满足个人与可选团队交集。
  if(Objects.equals(beforeUser,request.getTargetAssigneeUserId()) && Objects.equals(beforeTeam,request.getTargetTeamId()))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"目标负责人与团队未变化"); // 无责任变更不伪造交接历史。
  if(jdbc.update("UPDATE work_orders SET assignee_user_id=?,team_id=?,version=version+1 WHERE tenant_id=? AND id=? AND version=? AND status IN ('PENDING','IN_PROGRESS')",
    request.getTargetAssigneeUserId(),request.getTargetTeamId(),tenant,id,version)!=1)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"工单状态或版本已变化"); // 原状态及版本共同限定。
  String detail="原负责人="+beforeUser+"，原团队="+beforeTeam+"；新负责人="+request.getTargetAssigneeUserId()+"，新团队="+request.getTargetTeamId()+"；原因："+request.getReason().trim(); // 理由保存在授权业务历史。
  if(jdbc.update("INSERT INTO work_order_logs(work_order_id,event_type,from_status,to_status,title,detail,actor_user_id) VALUES(?,'ASSIGNED',?,?,'工单责任交接',?,?)",id,status,status,detail,actor.getUserId())!=1)
   throw new IllegalStateException("改派日志保存失败"); // 历史写入失败必须回滚负责人。
  var changes=new LinkedHashMap<String,Object>(); // 统一审计不保存理由正文。
  changes.put("beforeAssigneeUserId",text(beforeUser));changes.put("afterAssigneeUserId",request.getTargetAssigneeUserId().toString()); // 审计ID字符串。
  changes.put("beforeTeamId",text(beforeTeam));changes.put("afterTeamId",text(request.getTargetTeamId())); // 保留个人/团队责任转换。
  changes.put("beforeVersion",version);changes.put("version",version+1);changes.put("reasonPresent",true); // 有限版本及固定理由标记。
  audit.appendSuccess(new AuditAppendCommand(tenant,actor.getUserId(),AuditAction.WORK_ORDER_REASSIGNED,AuditObjectType.WORK_ORDER,id,changes,null,null)); // 与业务同事务。
  WorkOrderVO result=workOrders.getWorkOrderDetail(id); // 新负责人、版本及历史一起作为幂等快照。
  if(jdbc.update("INSERT INTO work_order_reassignments(tenant_id,work_order_id,request_id,payload_hash,before_assignee_user_id,after_assignee_user_id,before_team_id,after_team_id,actor_user_id,reason,result_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
    tenant,id,key,hash,beforeUser,request.getTargetAssigneeUserId(),beforeTeam,request.getTargetTeamId(),actor.getUserId(),request.getReason().trim(),json.writeValueAsString(result))!=1)
   throw new IllegalStateException("改派结果保存失败"); // 结果记录失败同样不留下半次交接。
  return result; // 事务代理提交后再向调用方返回。
 }
 private void validate(Long id,WorkOrderReassignDto r) {
  if(id==null||id<=0||r==null||r.getRequestId()==null||!r.getRequestId().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    ||r.getExpectedVersion()==null||r.getExpectedVersion()<0||r.getExpectedVersion()>MAX_VERSION
    ||r.getTargetAssigneeUserId()==null||r.getTargetAssigneeUserId()<=0||r.getTargetTeamId()!=null&&r.getTargetTeamId()<=0
    ||r.getReason()==null||r.getReason().trim().isEmpty()||r.getReason().trim().length()>1000)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"改派参数无效"); // 直接服务调用也执行全部输入检查。
 }
 private String hash(String value) {
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));} // 固定SHA256，不保存原请求正文到审计。
  catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("改派请求指纹不可用",failure);} // 不得退回无指纹幂等。
 }
 private Long number(Object value){return value==null?null:((Number)value).longValue();} // JDBC允许空旧负责人/团队。
 private String text(Long value){return value==null?null:value.toString();} // JSON ID保持精确。
}
