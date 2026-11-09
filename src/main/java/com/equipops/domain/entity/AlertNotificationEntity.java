package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.equipops.domain.enums.AlertNotificationStatus;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 首版仅站内通知，无短信或邮件发送能力。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("alert_notifications")
public class AlertNotificationEntity {
    /** 主键ID，由数据库生成。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属企业，后续由可信上下文或任务记录确定，不从浏览器接收。 */
    private Long tenantId;

    /** 通知关联的告警事件ID。 */
    private Long eventId;

    /** 接收者用户ID，读取时重查企业及管理员权限。 */
    private Long recipientUserId;

    /** 服务端幂等投递键，数据库步骤需设计唯一约束。 */
    private String deliveryKey;

    /** 投递状态；DELIVERED不等于已读。 */
    private AlertNotificationStatus status;

    /** 固定模板产生的安全标题。 */
    private String title;

    /** 脱敏摘要，不包含导出筛选、文件路径或原始异常。 */
    private String message;

    /** 已经尝试投递的次数。 */
    private Integer attempts;

    /** 允许下一次有限重试的时间。 */
    private LocalDateTime nextAttemptAt;

    /** 投递失败白名单编码。 */
    private String safeErrorCode;

    /** 站内提醒持久化完成时间。 */
    private LocalDateTime deliveredAt;

    /** 用户阅读时间；读取不自动等同确认接手。 */
    private LocalDateTime readAt;

    /** 记录创建时间，后续数据库步骤确定生成策略。 */
    private LocalDateTime createdAt;

    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
