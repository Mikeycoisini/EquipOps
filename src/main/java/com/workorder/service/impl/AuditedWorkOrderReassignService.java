package com.workorder.service.impl;
import com.audit.service.AuditEventService;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.dto.workorder.WorkOrderReassignDto;
import com.equipops.domain.enums.*;
import com.equipops.domain.vo.WorkOrderVO;
import com.workorder.service.WorkOrderReassignService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
/** 非事务失败编排，避免原工单回滚和独立审计争用行锁。 */
@Service @Primary
public class AuditedWorkOrderReassignService implements WorkOrderReassignService {
 private static final Logger LOG=LoggerFactory.getLogger(AuditedWorkOrderReassignService.class);
 private final WorkOrderReassignService delegate;private final CurrentAccessService access;private final AuditEventService audit;
 public AuditedWorkOrderReassignService(@Qualifier("workOrderReassignServiceImpl")WorkOrderReassignService delegate,CurrentAccessService access,AuditEventService audit){this.delegate=delegate;this.access=access;this.audit=audit;}
 /** 1.固定可信身份；2.等待事务结束；3.失败独立审计；4.原异常继续传播。 */
 @Override public WorkOrderVO reassign(Long id,WorkOrderReassignDto request) {
  var actor=access.requireTenantAccess(); // 不从目标工单或正文猜测企业。
  try{return delegate.reassign(id,request);} // 成功事务及成功审计提交后返回。
  catch(RuntimeException original) {
   int status=original instanceof ResponseStatusException response?response.getStatusCode().value():500; // 只采集固定状态。
   String code=switch(status){case 400->"INVALID_ARGUMENT";case 401->"UNAUTHORIZED";case 403->"FORBIDDEN";case 404->"NOT_FOUND";case 409->"CONFLICT";default->"INTERNAL_ERROR";}; // 不使用异常正文。
   var event=new AuditAppendCommand(actor.getTenant().getId(),actor.getUserId(),AuditAction.WORK_ORDER_REASSIGNED,AuditObjectType.WORK_ORDER,null,Map.of(),code,null); // 目标未成功确认故留空。
   try{if(status==401||status==403)audit.appendDenied(event);else audit.appendFailure(event);} // 原业务已回滚，独立提交结果。
   catch(RuntimeException unavailable){LOG.warn("改派失败审计未写入，code={}",code);} // 审计不可用也保留原异常。
   throw original; // 禁止伪造成功或覆盖真实业务错误。
  }
 }
}
