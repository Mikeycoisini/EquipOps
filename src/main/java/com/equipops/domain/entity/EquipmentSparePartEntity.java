package com.equipops.domain.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 具体设备与备件的适配关系，不代表库存或实际领料。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("equipment_spare_parts")
public class EquipmentSparePartEntity {
    /** 适配关系主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属企业。 */
    private Long tenantId;

    /** 关联设备ID。 */
    private Long equipmentId;

    /** 适用备件ID。 */
    private Long sparePartId;

    /** 创建人用户ID；账号物理删除后可为空。 */
    private Long createdBy;

    /** 关系建立时间，由数据库生成。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

}
