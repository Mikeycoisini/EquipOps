package com.equipops.domain.dto.workorder;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 保存工单备件请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderPartUpsertDto {

    /** 关联备件 ID */
    private Long sparePartId;

    /** 计划使用数量 */
    private BigDecimal plannedQuantity;
}
