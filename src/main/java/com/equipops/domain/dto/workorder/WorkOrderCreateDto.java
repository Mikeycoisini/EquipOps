package com.equipops.domain.dto.workorder;

import com.equipops.domain.enums.WorkOrderPriority;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 创建工单请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderCreateDto {

    /** 工单编号 */
    private String orderNo;

    /** 标题 */
    private String title;

    /** 关联设备 ID */
    private Long equipmentId;

    /** 描述 */
    private String description;

    /** 工单优先级 */
    private WorkOrderPriority priority;

    /** 工单负责人用户 ID */
    private Long assigneeUserId;

    /** 截止时间 */
    private LocalDateTime dueAt;

    /** 创建人用户 ID */
    private Long createdBy;

    private String faultCategory;
}
