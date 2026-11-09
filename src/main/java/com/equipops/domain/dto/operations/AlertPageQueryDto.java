package com.equipops.domain.dto.operations;

import com.equipops.domain.enums.AlertEventStatus;
import com.equipops.domain.enums.AlertRecoveryStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 当前企业管理员的告警分页条件；不接受tenantId或操作者身份。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertPageQueryDto {
    /** 页码，默认1。 */
    @Min(1)
    private long current = 1;

    /** 每页条数，默认10，上限100。 */
    @Min(1)
    @Max(100)
    private long size = 10;

    /** 可选人工处置状态。 */
    private AlertEventStatus status;

    /** 可选检测恢复状态。 */
    private AlertRecoveryStatus recoveryStatus;

    /** 可选规则ID，须校验企业范围。 */
    @Positive
    private Long ruleId;
}
