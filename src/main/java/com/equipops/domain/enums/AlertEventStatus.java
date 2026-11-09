package com.equipops.domain.enums;

/** 告警人工处置生命周期；不代表故障恢复检测结果。 */
public enum AlertEventStatus {
    /** 待处理。 */
    OPEN,
    /** 管理员已确认接手。 */
    ACKNOWLEDGED,
    /** 已满足后续处置规则并结案，不代表历史失败任务重新成功。 */
    RESOLVED
}
