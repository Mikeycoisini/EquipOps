package com.tenant.service.impl;

import com.audit.service.AuditEventService;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.dto.tenant.*;
import com.equipops.domain.enums.*;
import com.equipops.domain.vo.TenantMemberVO;
import com.tenant.service.TenantMemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.function.Supplier;

/** 非事务编排入口：等待业务代理提交/回滚后，独立追加失败或拒绝记录。 */
@Service
@Primary
public class AuditedTenantMemberService implements TenantMemberService {
    private static final Logger LOG = LoggerFactory.getLogger(AuditedTenantMemberService.class);
    private final TenantMemberService delegate;
    private final AuditEventService audit;
    private final CurrentAccessService access;

    public AuditedTenantMemberService(@Qualifier("tenantMemberServiceImpl") TenantMemberService delegate,
                                     AuditEventService audit, CurrentAccessService access) {
        this.delegate = delegate;
        this.audit = audit;
        this.access = access;
    }

    @Override
    public IPage<TenantMemberVO> getMemberPage(long current, long size, String keyword, Boolean enabled) {
        // 只读查询不全量写入审计，仍由原服务逐次授权。
        return delegate.getMemberPage(current, size, keyword, enabled);
    }

    @Override
    public TenantMemberVO addMember(TenantMemberCreateDto request) {
        // 1. 固定动作编码；2. 委托事务服务；3. 失败在事务结束后记录。
        return execute(AuditAction.MEMBER_ADDED, () -> delegate.addMember(request));
    }

    @Override
    public TenantMemberVO updateMemberRoles(Long id, TenantMemberRolesUpdateDto request) {
        // 1. 固定动作编码；2. 保留原角色规则；3. 失败在事务结束后记录。
        return execute(AuditAction.MEMBER_ROLES_CHANGED, () -> delegate.updateMemberRoles(id, request));
    }

    @Override
    public TenantMemberVO updateMemberStatus(Long id, TenantMemberStatusUpdateDto request) {
        // 1. 固定动作编码；2. 保留最后管理员规则；3. 失败在事务结束后记录。
        return execute(AuditAction.MEMBER_STATUS_CHANGED, () -> delegate.updateMemberStatus(id, request));
    }

    private TenantMemberVO execute(AuditAction action, Supplier<TenantMemberVO> operation) {
        // 1. 先建立可信当前企业身份；无法确认企业时不猜测归属、不记录客户端企业ID。
        TenantAccess actor = access.requireTenantAccess();
        try {
            // 2. 委托Spring事务代理，返回前业务与成功审计已共同提交或回滚。
            return operation.get();
        } catch (RuntimeException original) {
            // 3. 固定状态映射，绝不读取异常消息或客户端正文作为审计摘要。
            int status = original instanceof ResponseStatusException response ? response.getStatusCode().value() : 500;
            // 4. 不保存未成功解析的目标ID，避免把猜测的跨企业对象当成已确认对象。
            AuditAppendCommand command = new AuditAppendCommand(actor.getTenant().getId(), actor.getUserId(),
                    action, AuditObjectType.MEMBER, null, Map.of(), errorCode(status), null);
            try {
                // 5. 拒绝与失败分别独立提交，不受已回滚业务事务影响。
                if (status == 401 || status == 403) audit.appendDenied(command); else audit.appendFailure(command);
            } catch (RuntimeException unavailable) {
                // 6. 审计异常不替换原始业务错误，也不把其正文或SQL写到日志。
                LOG.warn("企业成员失败审计未写入，action={}, code={}", action, command.getErrorCode());
            }
            // 7. 保留原异常实例和HTTP语义，禁止将失败转换成成功。
            throw original;
        }
    }

    private String errorCode(int status) {
        // 只返回固定安全码，原始异常与请求值不能流入此字段。
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
