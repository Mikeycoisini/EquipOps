package com.tenant.service.impl;

import com.audit.service.AuditEventService;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.tenant.SiteScopeUpdateDto;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.enums.AuditAction;
import com.equipops.domain.enums.AuditObjectType;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import com.equipops.domain.vo.SiteScopeVO;
import com.tenant.service.SiteScopeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** 范围敏感变更的非事务编排入口。 */
@Service
@Primary
public class AuditedSiteScopeService implements SiteScopeService {
    private static final Logger LOG = LoggerFactory.getLogger(AuditedSiteScopeService.class);
    private final SiteScopeService delegate;
    private final AuditEventService audit;
    private final CurrentAccessService access;
    public AuditedSiteScopeService(@Qualifier("siteScopeServiceImpl") SiteScopeService delegate, AuditEventService audit, CurrentAccessService access) {
        this.delegate=delegate;this.audit=audit;this.access=access;
    }
    @Override
    public SiteScopeVO getScopes(Long membershipId) {
        // 1. 只读查询仍由事务服务每次管理员鉴权，不批量生成查询审计。
        return delegate.getScopes(membershipId);
    }

    @Override
    public SiteScopeVO updateScopes(Long membershipId, SiteScopeUpdateDto request) {
        // 1. 先取得可信企业与操作者，不能用请求中的成员或厂区反推授权。
        var actor = access.requireTenantAccess();
        try {
            // 2. 委托事务代理，成功返回前业务和成功审计已共同提交。
            return delegate.updateScopes(membershipId, request);
        } catch (RuntimeException original) {
            // 3. 此处事务已回滚，失败审计可独立提交，不和原业务行锁竞争。
            int status = original instanceof ResponseStatusException response ? response.getStatusCode().value() : 500;
            // 4. 未成功确认的目标ID与异常正文不进入失败记录。
            var command = new AuditAppendCommand(actor.getTenant().getId(), actor.getUserId(),
                    AuditAction.MEMBER_SITE_SCOPES_CHANGED, AuditObjectType.MEMBER, null,
                    Map.of(), errorCode(status), null);
            try {
                // 5. 拒绝与失败分开记录，审计不可用时也必须保留原始业务错误。
                if (status == 401 || status == 403) audit.appendDenied(command); else audit.appendFailure(command);
            } catch (RuntimeException unavailable) {
                // 6. 日志只记录固定错误码，不保存异常消息、SQL或请求内容。
                LOG.warn("成员厂区范围失败审计未写入，code={}", command.getErrorCode());
            }
            // 7. 原始失败继续向HTTP层传播，禁止伪造成功。
            throw original;
        }
    }

    private String errorCode(int status) {
        // 8. 失败审计仅接受固定安全码，不使用异常消息作为摘要。
        return switch (status) {
            case 400 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            default -> "INTERNAL_ERROR";
        };
    }
}
