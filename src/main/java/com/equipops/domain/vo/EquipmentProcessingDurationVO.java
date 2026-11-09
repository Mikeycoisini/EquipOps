package com.equipops.domain.vo;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 设备工单平均总处理时长排行，包含等待和验收时间，不等于实际维修工时。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EquipmentProcessingDurationVO {
    /** 当前企业中的设备ID。 */
    private Long equipmentId;
    /** 设备当前资产编号。 */
    private String assetCode;
    /** 设备当前名称。 */
    private String equipmentName;
    /** 参与本次统计的有效已完成工单数。 */
    private Long completedOrderCount;
    /** 从创建到验收完成的平均秒数，保留两位小数，包含等待时间。 */
    private BigDecimal avgProcessingSeconds;
}
