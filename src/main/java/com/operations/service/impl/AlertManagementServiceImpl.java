package com.operations.service.impl;
import com.auth.service.CurrentAccessService;
import com.auth.model.AuthSessionAttributes;
import com.common.logging.SafeMessageConverter;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.operations.*;
import com.equipops.domain.entity.*;
import com.equipops.domain.enums.*;
import com.equipops.domain.vo.*;
import com.operations.mapper.*;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AlertManagementServiceImpl {
    // 当前请求实时身份校验。
    private final CurrentAccessService access;
    // 管理数据访问。
    private final AlertManagementMapper management;
    // 规则锁和数据库时间。
    private final AlertRuleMapper rules;
    // 当前读复核管理员资格。
    private final AlertDetectionMapper detection;
    // 公开告警查询。
    private final AlertQueryServiceImpl queries;
    // 短事务模板。
    private final TransactionTemplate transactions;

    public AlertRuleEntity getRule() {
        // 1. 校验当前企业管理员，失败返回401/403或企业上下文错误。
        // 2. 查询当前企业固定规则；没有配置时返回404供页面显示未配置。
        // 3. 返回企业内部配置，不返回其他企业资料。
        // 取得可信企业ID。
        Long tenantId = access.requireTenantAdminTenantId();
        // 查询已存在配置。
        AlertRuleEntity rule = management.selectRule(tenantId);
        // 明确区分未配置。
        if (rule == null) {
            // 返回配置不存在。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚未配置导出告警规则");
        }
        // 返回当前企业配置。
        return rule;
    }

    public AlertRuleEntity saveRule(AlertRuleSaveDto request) {
        // 1. 验证管理员及所有配置范围，参数非法返回400。
        // 2. 短事务锁定规则，再验证负责人有效管理员资格。
        // 3. 首次创建要求expectedVersion为空；修改要求版本匹配，否则409。
        // 4. 保存并提交，返回数据库最新版本；异常全部回滚。
        // 获取可信企业身份。
        Long tenantId = access.requireTenantAdminTenantId();
        // 服务内部调用同样检查配置边界。
        if (request == null || request.getEnabled() == null || request.getOwnerUserId() == null || request.getOwnerUserId() <= 0
                || request.getThresholdCount() == null || request.getThresholdCount() < 1 || request.getThresholdCount() > 10000
                || request.getWindowSeconds() == null || request.getWindowSeconds() < 1 || request.getWindowSeconds() > 86400
                || request.getCooldownSeconds() == null || request.getCooldownSeconds() < 0 || request.getCooldownSeconds() > 86400) {
            // 拒绝非法参数。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "告警规则参数无效");
        }
        // 在一个短事务中保存规则。
        return transactions.execute(status -> {
            // 先锁规则，与检测采用相同次序。
            AlertRuleEntity rule = management.lockRule(tenantId);
            // 检查负责人是否仍有本企业管理员资格。
            if (detection.selectRecipientForShare(tenantId, request.getOwnerUserId()) == null) {
                // 拒绝其他企业或失效负责人。
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是本企业有效管理员");
            }
            // 区分创建与更新。
            boolean creating = rule == null;
            // 检查创建/更新的版本意图。
            if ((creating && request.getExpectedVersion() != null)
                    || (!creating && !Objects.equals(rule.getVersion(), request.getExpectedVersion()))) {
                // 拒绝覆盖他人的配置。
                throw new ResponseStatusException(HttpStatus.CONFLICT, "告警配置已变化，请刷新后重试");
            }
            // 创建时准备空实体。
            if (creating) {
                // 使用set风格填充字段。
                rule = new AlertRuleEntity();
            }
            // 由服务端填写企业归属。
            rule.setTenantId(tenantId);
            // 固定首版规则类型。
            rule.setRuleCode(AlertRuleCode.EXPORT_TASK_SYSTEM_FAILURE);
            // 设置开关。
            rule.setEnabled(request.getEnabled());
            // 设置阈值。
            rule.setThresholdCount(request.getThresholdCount());
            // 设置窗口。
            rule.setWindowSeconds(request.getWindowSeconds());
            // 设置冷却。
            rule.setCooldownSeconds(request.getCooldownSeconds());
            // 设置已验证的负责人。
            rule.setOwnerUserId(request.getOwnerUserId());
            // 创建或按版本更新。
            int changed = creating ? management.insertRule(rule) : management.updateRule(rule, request.getExpectedVersion());
            // 保存失败回滚。
            if (changed != 1) {
                // 返回状态竞争。
                throw new ResponseStatusException(HttpStatus.CONFLICT, "告警配置保存冲突");
            }
            // 当前事务读取数据库版本并返回。
            return management.selectRule(tenantId);
        });
    }

    public AlertEventVO act(Long id, AlertActionDto request, AlertActionType type) {
        // 1. 校验管理员、告警可见性、版本及非空说明。
        // 2. 先锁规则再锁事件，匹配期望版本及允许状态。
        // 3. OPEN可确认，OPEN/ACK可备注，ACK可结案；终态返回409。
        // 4. 状态版本与脱敏时间线同事务保存；人工结案保留检测恢复状态。
        // 5. 提交后返回最新公开详情。
        // 获取当前企业。
        Long tenantId = access.requireTenantAdminTenantId();
        // 获取真实操作者。
        Long userId = access.requireEnabledUserId();
        // 校验动作参数。
        if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() < 0
                || request.getNote() == null || request.getNote().isBlank() || request.getNote().length() > 1000 || type == null) {
            // 拒绝缺少说明或版本。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写处置说明并提供有效版本");
        }
        // 先通过公开查询确认企业范围。
        AlertEventVO visible = queries.detail(id);
        // 执行动作短事务。
        transactions.executeWithoutResult(status -> {
            // 按检测相同顺序先锁规则。
            rules.selectRuleForUpdate(tenantId, visible.getRuleId());
            // 再锁当前企业告警。
            AlertEventEntity event = management.lockEvent(tenantId, id);
            // 检查可见性及版本。
            if (event == null || !Objects.equals(event.getVersion(), request.getExpectedVersion())) {
                // 返回并发冲突。
                throw new ResponseStatusException(HttpStatus.CONFLICT, "告警已更新，请刷新后重试");
            }
            // 终态禁止追加处置。
            if (event.getStatus() == AlertEventStatus.RESOLVED
                    || (type == AlertActionType.ACKNOWLEDGE && event.getStatus() != AlertEventStatus.OPEN)
                    || (type == AlertActionType.RESOLVE && event.getStatus() != AlertEventStatus.ACKNOWLEDGED)) {
                // 返回非法状态。
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不允许该操作");
            }
            // 写入前复核操作者最新管理员资格。
            if (detection.selectRecipientForShare(tenantId, userId) == null) {
                // 资格失效回滚。
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理员资格已失效");
            }
            // 条件更新事件状态及版本。
            if (management.updateState(tenantId, id, event.getVersion(), type.name()) != 1) {
                // 保持状态与时间线原子性。
                throw new ResponseStatusException(HttpStatus.CONFLICT, "告警状态更新冲突");
            }
            // 创建处置记录。
            AlertActionEntity action = new AlertActionEntity();
            // 填写企业范围。
            action.setTenantId(tenantId);
            // 填写告警ID。
            action.setEventId(id);
            // 填写真实操作者。
            action.setActorUserId(userId);
            // 动作类型来自服务端路由。
            action.setActionType(type);
            // 使用既有脱敏函数处理说明。
            action.setNote(SafeMessageConverter.sanitize(request.getNote().trim()));
            // 时间线保存失败回滚状态变化。
            if (management.insertAction(action) != 1) {
                // 抛出异常触发回滚。
                throw new IllegalStateException("处置记录未保存");
            }
        });
        // 返回本企业最新公开信息。
        return queries.detail(id);
    }

    public List<AlertActionVO> actions(Long id) {
        // 1. 实时校验管理员及详情范围，其他企业返回404。
        // 2. 返回最近200条处置记录，按ID倒序。
        // 取得企业身份。
        Long tenantId = access.requireTenantAdminTenantId();
        // 复核目标可见。
        queries.detail(id);
        // 返回当前企业时间线。
        return management.selectActions(tenantId, id);
    }

    public IPage<AlertNotificationVO> notifications(long current, long size) {
        // 1. 校验管理员身份与分页范围。
        // 2. 仅按当前企业和本人接收者返回已投递通知。
        // 获取企业身份。
        Long tenantId = access.requireTenantAdminTenantId();
        // 获取本人ID。
        Long userId = access.requireEnabledUserId();
        // 限制分页大小。
        if (current < 1 || size < 1 || size > 100) {
            // 拒绝无界读取。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知分页参数有误");
        }
        // 查询本人已投递通知。
        return management.selectNotifications(new Page<>(current, size), tenantId, userId);
    }

    public void readNotification(Long id) {
        // 1. 验证管理员、当前企业及接收者身份。
        // 2. 不可见通知返回404；可见通知以COALESCE幂等设置首次已读时间。
        // 获取企业身份。
        Long tenantId = access.requireTenantAdminTenantId();
        // 获取本人ID。
        Long userId = access.requireEnabledUserId();
        // 检查通知归属。
        if (id == null || id <= 0 || management.selectOwnNotification(tenantId, userId, id) == null) {
            // 隐藏其他用户或企业通知。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        }
        // 幂等保存首次已读时间。
        management.markRead(tenantId, userId, id);
    }
}
