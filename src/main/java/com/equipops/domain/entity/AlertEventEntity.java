package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.equipops.domain.enums.AlertEventStatus;
import com.equipops.domain.enums.AlertRecoveryStatus;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 按企业、规则和故障类型合并的告警事件；不改变导出任务状态。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("alert_events")
public class AlertEventEntity {
    /** 主键ID，由数据库生成。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属企业，后续由可信上下文或任务记录确定，不从浏览器接收。 */
    private Long tenantId;

    /** 规则ID，须与企业归属一致。 */
    private Long ruleId;

    /** 服务端生成的企业+规则+故障类型指纹，不作为授权凭证。 */
    private String fingerprint;

    /** 白名单系统故障编码，不记录原始异常。 */
    private String errorCode;

    /** 人工处置状态。 */
    private AlertEventStatus status;

    /** 独立恢复检测状态。 */
    private AlertRecoveryStatus recoveryStatus;

    /** 活动事件为1；结案后NULL，后续与指纹建立唯一约束。 */
    private Integer openSlot;

    /** 已去重计入的失败任务数量，不能按扫描次数累加。 */
    private Long occurrenceCount;

    /** 首次触发的导出任务ID。 */
    private Long firstTaskId;

    /** 最近一次计入的导出任务ID；不能单独作为去重证据。 */
    private Long latestTaskId;

    /** 事件负责人，首次触发时从规则取得，后续仍需实时鉴权。 */
    private Long ownerUserId;

    /** 首次观测时间。 */
    private LocalDateTime firstSeenAt;

    /** 最近新故障观测时间。 */
    private LocalDateTime lastSeenAt;

    /** 确认接手时间。 */
    private LocalDateTime acknowledgedAt;

    /** 检测确认恢复时间，未确认为空。 */
    private LocalDateTime recoveredAt;

    /** 人工结案时间。 */
    private LocalDateTime resolvedAt;

    /** 条件更新版本，防止并发确认或处置互相覆盖。 */
    private Long version;

    /** 记录创建时间，后续数据库步骤确定生成策略。 */
    private LocalDateTime createdAt;

    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
