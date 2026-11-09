package com.equipops.domain.vo;

import com.equipops.domain.enums.WorkOrderPriority;
import com.equipops.domain.enums.WorkOrderStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 维修工单。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderVO {

    /** 维修工单 ID */
    private Long id;

    /** 所属企业 ID。 */
    private Long tenantId;

    /** 所属厂区 ID。 */
    private Long siteId;


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

    /** 工单状态 */
    private WorkOrderStatus status;

    /** 工单负责人用户 ID */
    private Long assigneeUserId;

    /** 截止时间 */
    private LocalDateTime dueAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 创建人用户 ID */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 工单处理时间线 */
    private List<WorkOrderLogVO> timeline;

    /** 关联设备档案，仅详情接口加载；其他工单接口未加载时为 null。 */
    private EquipmentVO equipment;

    /** 工单用料汇总，仅详情接口加载；空列表表示当前工单没有可见用料。 */
    private List<WorkOrderPartVO> parts;
}
