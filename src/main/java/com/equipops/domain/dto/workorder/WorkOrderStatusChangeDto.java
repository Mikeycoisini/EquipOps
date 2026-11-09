package com.equipops.domain.dto.workorder;

import com.equipops.domain.enums.WorkOrderStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工单状态变更请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderStatusChangeDto {

    /** 工单状态 */
    private WorkOrderStatus status;

    /** 事件详情 */
    private String detail;

    /** 操作人用户 ID */
    private Long actorUserId;
}
