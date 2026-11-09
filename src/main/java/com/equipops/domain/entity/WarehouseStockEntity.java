package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

/** 仓库库存余额；目录资料保存在 spare_parts，数量按企业、仓库、备件分别记账。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("warehouse_stock")
public class WarehouseStockEntity {
    /** 库存记录主键，由数据库生成。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属企业，由服务端登录上下文确定。 */
    private Long tenantId;
    /** 所属仓库，必须与备件属于同一企业。 */
    private Long warehouseId;
    /** 关联备件主键。 */
    private Long sparePartId;
    /** 当前库存数量，最多三位小数，不允许负数。 */
    private BigDecimal quantity;
    /** 当前仓库对此备件的安全库存；不读取目录中的旧阈值。 */
    private BigDecimal minStockQuantity;
    /** 建账时间，由数据库维护。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    /** 最后变动时间，由数据库维护。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
