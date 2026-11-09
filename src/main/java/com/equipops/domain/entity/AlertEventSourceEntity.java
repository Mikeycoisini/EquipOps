package com.equipops.domain.entity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
/** 已识别的失败任务证据，eventId为空表示尚未达到归并条件。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertEventSourceEntity {
    /** 证据ID。 */
    private Long id;
    /** 企业ID。 */
    private Long tenantId;
    /** 规则ID。 */
    private Long ruleId;
    /** 已归属事件ID。 */
    private Long eventId;
    /** 原失败任务ID。 */
    private Long taskId;
    /** 系统故障编码。 */
    private String errorCode;
    /** 原任务完成时间。 */
    private LocalDateTime taskFinishedAt;
    /** 首次记录证据的时间。 */
    private LocalDateTime createdAt;
}
