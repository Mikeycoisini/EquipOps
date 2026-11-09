package com.equipops.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

/** 已建账的仓库余额及关联目录资料；没有记录不等于已确认零库存。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseStockVO {
    /** 库存记录 ID。 */
    private Long id;
    /** 所属企业 ID。 */
    private Long tenantId;
    /** 仓库 ID。 */
    private Long warehouseId;
    /** 仓库所属厂区 ID。 */
    private Long siteId;
    /** 仓库编号。 */
    private String warehouseCode;
    /** 仓库名称。 */
    private String warehouseName;
    /** 仓库是否启用；停用仓库的已有余额仍允许查询。 */
    private Boolean warehouseEnabled;
    /** 备件 ID。 */
    private Long sparePartId;
    /** 备件编号。 */
    private String partCode;
    /** 备件名称。 */
    private String partName;
    /** 备件规格。 */
    private String spec;
    /** 计量单位。 */
    private String unit;
    /** 备件是否启用；停用不隐藏已有库存。 */
    private Boolean sparePartEnabled;
    /** 当前仓库的实际账面数量。 */
    private BigDecimal quantity;
    /** 当前仓库的安全库存阈值。 */
    private BigDecimal minStockQuantity;
    /** 数量严格小于安全库存时为 true，等于阈值时为 false。 */
    private Boolean lowStock;
    /** 建账时间。 */
    private LocalDateTime createdAt;
    /** 最后变动时间。 */
    private LocalDateTime updatedAt;
}
