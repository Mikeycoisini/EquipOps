package com.equipops.domain.dto.workorder;

import com.equipops.domain.enums.WorkOrderEventType;
import com.equipops.domain.enums.WorkOrderStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 新增工单日志请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderLogCreateDto {

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
}
