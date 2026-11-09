package com.equipops.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 备件。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SparePartVO {

    /** 备件 ID */
    private Long id;

    /** 所属企业 ID。 */
    private Long tenantId;


    /** 备件编号 */
    private String partCode;

    /** 备件名称 */
    private String name;

    /** 规格型号 */
    private String spec;

    /** 分类 */
    private String category;

    /** 所在位置或库位 */
    private String location;

    /** 当前库存数量 */
    private BigDecimal stockQuantity;

    /** 安全库存数量 */
    private BigDecimal minStockQuantity;

    /** 计量单位 */
    private String unit;

    /** 是否启用 */
    private Boolean enabled;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
