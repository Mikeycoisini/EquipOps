package com.operations.service.impl;
import com.operations.mapper.*;
import com.equipops.domain.entity.*;
import com.equipops.domain.enums.*;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
@Service
@RequiredArgsConstructor
public class AlertRecoveryServiceImpl {
    // 查询规则与数据库时间。
    private final AlertRuleMapper rules;
    // 查询候选、锁定事件及条件更新恢复状态。
    private final AlertManagementMapper management;
    // 当前读复核企业和负责人资格。
    private final AlertDetectionMapper detection;
    // 每条候选独立短事务。
    private final TransactionTemplate transactions;

    public void detectRecoveredEvents() {
        // 1. 选取最多100条满足静默窗口及后续成功导出条件的活动候选。
        // 2. 每条事务按规则、事件次序取锁，复核启用及UNVERIFIED状态。
        // 3. 取得数据库时间，确认完整窗口内无同类失败，且新故障之后有成功导出。
        // 4. 当前读检查企业和负责人有效，按版本写RECOVERED及数据库时间。
        // 5. 回滚当前异常事务，已提交事件保留，人工处置状态独立保存。
        // 分批选取可恢复候选。
        for (AlertEventEntity candidate : management.recoveryCandidates()) {
            // 线程中断时结束本轮。
            if (Thread.currentThread().isInterrupted()) {
                // 保留中断标记供调用者感知。
                return;
            }
            // 每条事件在短事务内复核。
            transactions.executeWithoutResult(status -> {
                // 与检测和处置统一先锁规则。
                AlertRuleEntity rule = rules.selectRuleForUpdate(candidate.getTenantId(), candidate.getRuleId());
                // 规则停用则停止该条恢复。
                if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
                    // 结束当前回调。
                    return;
                }
                // 再锁目标事件。
                AlertEventEntity event = management.lockEvent(candidate.getTenantId(), candidate.getId());
                // 终态或已恢复事件无需再次更新。
                if (event == null || event.getStatus() == AlertEventStatus.RESOLVED || event.getRecoveryStatus() != AlertRecoveryStatus.UNVERIFIED) {
                    // 结束当前回调。
                    return;
                }
                // 取得数据库当前时间。
                LocalDateTime now = rules.selectDatabaseNow();
                // 完整规则窗口尚未经过时保持原状态。
                if (event.getLastSeenAt().plusSeconds(rule.getWindowSeconds()).isAfter(now)) {
                    // 等待后续检测。
                    return;
                }
                // 当前读复核窗口内是否又出现同类失败。
                if (management.recentFailure(event.getTenantId(), event.getErrorCode(), now.minusSeconds(rule.getWindowSeconds())) != null) {
                    // 有失败则继续保留UNVERIFIED。
                    return;
                }
                // 必须存在最后新故障观测之后的成功导出。
                if (management.laterSuccess(event.getTenantId(), event.getLastSeenAt()) == null) {
                    // 无实际成功证据时继续等待。
                    return;
                }
                // 复核企业与负责人资格。
                if (detection.selectRecipientForShare(event.getTenantId(), rule.getOwnerUserId()) == null) {
                    // 资格失效时保持原事件。
                    return;
                }
                // 仅在当前版本仍活动且未确认恢复时更新。
                if (management.markRecovered(event.getTenantId(), event.getId(), event.getVersion()) != 1) {
                    // 条件不一致触发回滚。
                    throw new IllegalStateException("告警恢复状态更新冲突");
                }
            });
        }
    }
}
