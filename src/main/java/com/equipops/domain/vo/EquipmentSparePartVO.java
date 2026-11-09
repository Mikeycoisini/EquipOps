package com.equipops.domain.vo;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 具体设备与备件的适配关系，不代表库存或实际领料。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EquipmentSparePartVO {
    /** 适配关系主键。 */
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
    private LocalDateTime createdAt;

    /** 当前设备资产编号。 */
    private String equipmentAssetCode;

    /** 当前设备名称。 */
    private String equipmentName;

    /** 当前备件编号。 */
    private String sparePartCode;

    /** 当前备件名称。 */
    private String sparePartName;

    /** 当前备件规格。 */
    private String sparePartSpec;

    /** 当前备件计量单位。 */
    private String sparePartUnit;

    /** 备件当前是否启用；停用不会删除已有适配关系。 */
    private Boolean sparePartEnabled;

}
