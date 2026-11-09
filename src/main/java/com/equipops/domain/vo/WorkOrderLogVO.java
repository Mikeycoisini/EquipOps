package com.equipops.domain.vo;

import com.equipops.domain.enums.WorkOrderEventType;
import com.equipops.domain.enums.WorkOrderStatus;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工单操作记录。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderLogVO {

    /** 工单操作记录 ID */
    private Long id;

    /** 关联工单 ID */
    private Long workOrderId;

    /** 工单事件类型 */
    private WorkOrderEventType eventType;

    /** 变更前状态 */
    private WorkOrderStatus fromStatus;

    /** 变更后状态 */
    private WorkOrderStatus toStatus;

    /** 标题 */
    private String title;

    /** 事件详情 */
    private String detail;

    /** 操作人用户 ID */
    private Long actorUserId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 操作人在本企业成员关系中关联的当前显示名；无法关联时为空，不是历史姓名快照。 */
    private String actorName;
}
