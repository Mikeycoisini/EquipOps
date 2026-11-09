package com.equipops.domain.enums;

/** 独立的检测结果；人工确认不能修改为已恢复。 */
public enum AlertRecoveryStatus {
    /** 尚无足够恢复依据。 */
    UNVERIFIED,
    /** 后续检测满足明确恢复规则；不代表原任务状态改变。 */
    RECOVERED
}
