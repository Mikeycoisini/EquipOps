package com.equipops.domain.vo;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Warehouse 查询返回数据。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseVO {
    /** 仓库 ID。 */
    private Long id;

    /** 企业 ID。 */
    private Long tenantId;

    /** 厂区 ID。 */
    private Long siteId;

    /** 仓库编号。 */
    private String warehouseCode;

    /** 仓库名称。 */
    private String name;

    /** 仓库位置。 */
    private String location;

    /** 是否启用。 */
    private Boolean enabled;

    /** 是否默认仓库。 */
    private Boolean isDefault;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
