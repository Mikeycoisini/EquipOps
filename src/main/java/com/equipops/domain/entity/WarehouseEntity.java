package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 企业仓库，使用 MyBatis-Plus 持久化。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("warehouses")
public class WarehouseEntity {
    /** 主键 ID，由数据库生成。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属企业 ID。 */
    private Long tenantId;

    /** 所属厂区 ID，厂区必须属于同一企业。 */
    private Long siteId;

    /** 仓库编号，在企业内唯一。 */
    private String warehouseCode;

    /** 仓库名称。 */
    private String name;

    /** 仓库位置。 */
    private String location;

    /** 是否启用。 */
    private Boolean enabled;

    /** 是否为企业默认仓库，每个企业最多一个。 */
    private Boolean isDefault;

    /** 创建时间，由数据库维护。 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 更新时间，由数据库维护。 */
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
