package com.operations.service;
import com.operations.service.impl.AlertRecoveryServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
/** 显式开启后运行，固定延迟避免单实例任务重叠。 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name="operations.alerts.enabled",havingValue="true")
public class AlertScheduler {
    // 只记录固定错误类型，避免泄露SQL和凭据。
    private static final Logger LOG = LoggerFactory.getLogger(AlertScheduler.class);
    // 有界失败任务检测入口。
    private final AlertDetectionService detection;
    // 独立恢复检测入口。
    private final AlertRecoveryServiceImpl recovery;
    @Scheduled(fixedDelayString="${operations.alerts.delay-ms:30000}",initialDelayString="${operations.alerts.initial-delay-ms:10000}")
    public void tick() {
        // 1. 执行一次有界失败扫描，位置随批次事务提交。
        // 2. 异常仅记录类型，由下一次固定延迟任务从数据库续扫。
        // 3. 再执行独立恢复检测，不把人工结案作为恢复信号。
        // 执行失败任务扫描。
        try {
            // 各实例通过规则锁及续扫版本竞争同一批次。
            detection.detectExportFailures();
        // 保留调度线程下一次继续工作的能力。
        } catch (RuntimeException failure) {
            // 仅输出异常类型。
            LOG.warn("告警扫描失败，type={}", failure.getClass().getSimpleName());
        }
        // 单独执行恢复复核。
        try {
            // 状态更新使用短事务和版本条件。
            recovery.detectRecoveredEvents();
        // 保留独立失败记录。
        } catch (RuntimeException failure) {
            // 仅输出异常类型。
            LOG.warn("告警恢复检测失败，type={}", failure.getClass().getSimpleName());
        }
    }
}
