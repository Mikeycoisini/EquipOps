package com.equipops.domain.enums;

/** 首版站内通知持久化投递状态，非已读状态。 */
public enum AlertNotificationStatus {
    /** 等待生成站内提醒。 */
    PENDING,
    /** 站内提醒已持久化，不等于用户已阅读。 */
    DELIVERED,
    /** 有限重试后仍失败。 */
    FAILED
}
