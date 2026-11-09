package com.equipops.domain.vo;

import com.equipops.domain.enums.AlertEventStatus;
import com.equipops.domain.enums.AlertRecoveryStatus;
import com.equipops.domain.enums.AlertRuleCode;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 管理员可见的告警信息；不暴露指纹、活动槽、投递键或SQL。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertEventVO {
    /** 告警事件ID。 */
    private Long id;

    /** 规则ID。 */
    private Long ruleId;

    /** 固定规则编码，后续查询关联规则取得。 */
    private AlertRuleCode ruleCode;

    /** 可公开的系统故障编码。 */
    private String errorCode;

    /** 人工处置状态。 */
    private AlertEventStatus status;

    /** 检测恢复状态。 */
    private AlertRecoveryStatus recoveryStatus;

    /** 已计入的去重失败任务数。 */
    private Long occurrenceCount;

    /** 管理员负责人ID。 */
    private Long ownerUserId;

    /** 首次观测时间。 */
    private LocalDateTime firstSeenAt;

    /** 最近观测时间。 */
    private LocalDateTime lastSeenAt;

    /** 确认时间。 */
    private LocalDateTime acknowledgedAt;

    /** 检测恢复时间。 */
    private LocalDateTime recoveredAt;

    /** 结案时间。 */
    private LocalDateTime resolvedAt;

    /** 条件更新版本，防止并发确认或处置互相覆盖。 */
    private Long version;
}
