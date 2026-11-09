package com.equipops.domain.vo;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 站内提醒公开字段；不暴露内部投递状态和失败细节。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertNotificationVO {
    /** 通知ID。 */
    private Long id;

    /** 点击后打开的告警ID，详情仍须鉴权。 */
    private Long eventId;

    /** 安全标题。 */
    private String title;

    /** 脱敏摘要。 */
    private String message;

    /** 已读时间，为空表示未读。 */
    private LocalDateTime readAt;

    /** 记录创建时间，后续数据库步骤确定生成策略。 */
    private LocalDateTime createdAt;
}
