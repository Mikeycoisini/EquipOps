package com.team.service;

import com.audit.service.AuditEventService;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.enums.*;
import com.team.dto.*;
import com.team.vo.TeamVO;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 非事务编排：等业务代理提交/回滚后，独立记录敏感写动作失败或拒绝。 */
@Service @Primary
public class AuditedTeamService implements TeamService {
    private static final Logger LOG=LoggerFactory.getLogger(AuditedTeamService.class);
    private final TeamService delegate;
    private final AuditEventService audit;
    private final CurrentAccessService access;
    public AuditedTeamService(@Qualifier("teamServiceImpl") TeamService delegate,AuditEventService audit,CurrentAccessService access){this.delegate=delegate;this.audit=audit;this.access=access;}
    @Override public List<TeamVO> list(){return delegate.list();}
    @Override public TeamVO detail(Long id){return delegate.detail(id);}
    @Override public TeamVO create(TeamCreateDto request){return execute(AuditAction.TEAM_CREATED,()->delegate.create(request));}
    @Override public TeamVO update(Long id,TeamUpdateDto request){return execute(AuditAction.TEAM_UPDATED,()->delegate.update(id,request));}
    @Override public TeamVO members(Long id,TeamMembersDto request){return execute(AuditAction.TEAM_MEMBERS_CHANGED,()->delegate.members(id,request));}
    @Override public TeamVO sites(Long id,TeamSitesDto request){return execute(AuditAction.TEAM_SITE_SCOPES_CHANGED,()->delegate.sites(id,request));}
    private TeamVO execute(AuditAction action,Supplier<TeamVO> operation) {
        // 1. 无可信身份时不猜测企业归属；所有目标都要由实际业务确认。
        var actor=access.requireTenantAccess();
        try {
            // 2. 成功事件在委托事务内追加，此处等待业务原子提交或回滚。
            return operation.get();
        } catch(RuntimeException original) {
            // 3. 失败只记录固定错误码，不保存目标猜测、团队名称或请求正文。
            int status=original instanceof ResponseStatusException response?response.getStatusCode().value():500;
            var command=new AuditAppendCommand(actor.getTenant().getId(),actor.getUserId(),action,AuditObjectType.TEAM,null,Map.of(),code(status),null);
            try {
                // 4. 独立失败事务在业务锁释放后运行，避免同一业务行锁竞争。
                if(status==401 || status==403)audit.appendDenied(command);else audit.appendFailure(command);
            } catch(RuntimeException unavailable) {
                // 5. 审计不可用不覆盖原始业务错误，日志仅记录固定动作。
                LOG.warn("团队失败审计未写入，action={}",action);
            }
            throw original;
        }
    }
    private String code(int status){return switch(status){case 400->"INVALID_ARGUMENT";case 401->"UNAUTHORIZED";case 403->"FORBIDDEN";case 404->"NOT_FOUND";case 409->"CONFLICT";default->"INTERNAL_ERROR";};}
}
