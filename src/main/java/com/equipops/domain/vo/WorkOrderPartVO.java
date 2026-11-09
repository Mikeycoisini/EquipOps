package com.equipops.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工单备件关联。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderPartVO {

    /** 工单备件关联 ID */
    private Long id;

    /** 关联工单 ID */
    private Long workOrderId;

    /** 关联备件 ID */
    private Long sparePartId;

    /** 备件编号，来自当前企业的备件目录。 */
    private String partCode;

    /** 备件名称，停用备件仍可作为历史用料展示。 */
    private String partName;

    /** 备件规格型号。 */
    private String spec;

    /** 计量单位，与计划、领用、退回数量对应。 */
    private String unit;

    /** 计划使用数量 */
    private BigDecimal plannedQuantity;

    /** 已领用数量 */
    private BigDecimal issuedQuantity;

    /** 已退回数量 */
    private BigDecimal returnedQuantity;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
