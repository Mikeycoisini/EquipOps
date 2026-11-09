package com.equipops.domain.dto.inventory;

import com.equipops.domain.enums.InventoryExportStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 本人在当前企业的导出任务分页条件；不接收其他用户或企业ID。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryExportPageQueryDto {
    /** 页码，默认第1页；小于1时后续业务按1处理。 */
    private long current = 1;

    /** 每页条数，默认10；后续业务限制为1到100。 */
    private long size = 10;

    /** 可选任务状态；为空时查询本人所有状态。 */
    private InventoryExportStatus status;
}
