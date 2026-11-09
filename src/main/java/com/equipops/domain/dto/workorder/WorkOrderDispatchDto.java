package com.equipops.domain.dto.workorder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工单派单请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderDispatchDto {

    /** 被派单的负责人用户 ID。 */
    private Long assigneeUserId;

    /** 执行派单操作的用户 ID。 */
    private Long actorUserId;
}
