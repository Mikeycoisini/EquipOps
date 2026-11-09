package com.equipops.domain.entity;
import java.time.LocalDateTime;
import lombok.Data;
/** 每条规则已提交批次的续扫位置。 */
@Data
public class AlertScanProgressEntity {
    /** 企业归属。 */
    private Long tenantId;
    /** 规则归属。 */
    private Long ruleId;
    /** 本轮使用的规则版本。 */
    private Long ruleVersion;
    /** 固定窗口开始。 */
    private LocalDateTime windowStart;
    /** 固定窗口结束。 */
    private LocalDateTime windowEnd;
    /** 已提交批次的最后完成时间。 */
    private LocalDateTime lastFinishedAt;
    /** 已提交批次的最后任务ID。 */
    private Long lastTaskId;
    /** 当前固定窗口是否已经读完。 */
    private Boolean completed;
    /** 续扫记录并发版本。 */
    private Long version;
    /** 最后一次成功扫描时间。 */
    private LocalDateTime updatedAt;
}
