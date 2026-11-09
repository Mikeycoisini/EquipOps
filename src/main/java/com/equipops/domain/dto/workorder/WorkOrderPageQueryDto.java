package com.equipops.domain.dto.workorder;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.*;
import com.equipops.domain.enums.WorkOrderStatus;
import com.equipops.domain.enums.WorkOrderPriority;

/** 列表查询条件；企业范围从登录上下文取得，不接收客户端 tenantId。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderPageQueryDto {
    /** 当前页码，小于1时按第1页处理。 */
    private long current = 1;

    /** 每页条数，业务层限制为1到100。 */
    private long size = 10;

    /** 工单编号或标题关键字。 */
    @Size(max = 200, message = "关键字不能超过200个字符")
    private String keyword;

    /** 工单状态，不传时不筛选。 */
    private WorkOrderStatus status;

    /** 工单优先级，不传时不筛选。 */
    private WorkOrderPriority priority;

    /** 关联设备 ID，必须为正数。 */
    @Positive(message = "设备 ID 必须大于0")
    private Long equipmentId;

    /** 工单负责人用户 ID，必须为正数。 */
    @Positive(message = "负责人 ID 必须大于0")
    private Long assigneeUserId;
    /** true只查询已逾期未完成工单；false或省略保留原查询。 */
    private Boolean overdueOnly;

    /** 保留原七参数构造方式，旧调用不启用逾期筛选。 */
    public WorkOrderPageQueryDto(long current, long size, String keyword, WorkOrderStatus status,
            WorkOrderPriority priority, Long equipmentId, Long assigneeUserId) {
        this(current, size, keyword, status, priority, equipmentId, assigneeUserId, null);
    }

}
