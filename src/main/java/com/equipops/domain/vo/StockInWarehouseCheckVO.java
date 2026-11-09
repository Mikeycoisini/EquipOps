package com.equipops.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 入库时查询仓库及所属厂区的可用状态。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockInWarehouseCheckVO {
    /** 当前企业内的仓库 ID。 */
    private Long warehouseId;

    /** 仓库是否启用。 */
    private Boolean warehouseEnabled;

    /** 同企业的所属厂区 ID；未关联到有效厂区记录时为空。 */
    private Long siteId;

    /** 所属厂区是否启用。 */
    private Boolean siteEnabled;
}
