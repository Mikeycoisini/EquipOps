package com.tenant.service.impl;

import com.audit.service.AuditEventService;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.tenant.SiteUpsertDto;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.enums.AuditAction;
import com.equipops.domain.enums.AuditObjectType;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import com.equipops.domain.vo.SiteVO;
import com.tenant.service.SiteService;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** 厂区敏感变更非事务编排入口，保留现有SiteService接口。 */
@Service
@Primary
public class AuditedSiteService implements SiteService {
    private static final Logger LOG = LoggerFactory.getLogger(AuditedSiteService.class);
    private final SiteService delegate;
    private final AuditEventService audit;
    private final CurrentAccessService access;
    public AuditedSiteService(@Qualifier("siteServiceImpl") SiteService delegate, AuditEventService audit, CurrentAccessService access) {
        this.delegate=delegate;this.audit=audit;this.access=access;
    }
    public List<SiteVO> getDefaultSites() {return delegate.getDefaultSites();}
    public List<SiteVO> getSiteCatalog() {return delegate.getSiteCatalog();}
    public SiteVO getSite(Long id) {return delegate.getSite(id);}
    @Override
    public SiteVO createSite(SiteUpsertDto request) {
        // 1. 固定厂区创建动作；成功事件由事务服务追加，失败在事务结束后处理。
        return execute(AuditAction.SITE_CREATED, () -> delegate.createSite(request));
    }
    @Override
    public SiteVO updateSite(Long id,SiteUpsertDto request) {
        // 1. 固定配置修改动作，不从请求正文推导审计身份或对象。
        return execute(AuditAction.SITE_UPDATED, () -> delegate.updateSite(id,request));
    }

    private SiteVO execute(AuditAction action, Supplier<SiteVO> operation) {
        // 1. 必须先建立可信当前企业身份，不能猜测客户端企业或目标归属。
        var actor = access.requireTenantAccess();
        try {
            // 2. 等待业务代理完成提交或回滚，成功事件与业务原子提交。
            return operation.get();
        } catch (RuntimeException original) {
            // 3. 只保存固定状态码，未确认的目标ID和原始异常正文不保存。
            int status = original instanceof ResponseStatusException response ? response.getStatusCode().value() : 500;
            var command = new AuditAppendCommand(actor.getTenant().getId(), actor.getUserId(), action,
                    AuditObjectType.SITE, null, Map.of(), errorCode(status), null);
            try {
                // 4. 失败和拒绝分别独立提交，避免原业务回滚清除结果。
                if (status == 401 || status == 403) audit.appendDenied(command); else audit.appendFailure(command);
            } catch (RuntimeException unavailable) {
                // 5. 审计存储失败只记录固定动作，不覆盖原始业务错误或输出异常正文。
                LOG.warn("厂区敏感动作失败审计未写入，action={}", action);
            }
            // 6. 保留原始HTTP语义，不能将失败伪装为成功。
            throw original;
        }
    }

    private String errorCode(int status) {
        // 1. 只映射固定程序错误码，不接受请求值或异常消息。
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
