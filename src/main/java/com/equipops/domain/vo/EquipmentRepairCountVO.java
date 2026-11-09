package com.equipops.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 设备报修频次排行项，统计工单创建次数，不表示同一故障的重复次数。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EquipmentRepairCountVO {
    /** 当前企业中的设备ID。 */
    private Long equipmentId;

    /** 设备当前资产编号。 */
    private String assetCode;

    /** 设备当前名称。 */
    private String equipmentName;

    /** 指定时间范围内创建的工单数，计入所有状态，按工单计数。 */
    private Long workOrderCount;
}
