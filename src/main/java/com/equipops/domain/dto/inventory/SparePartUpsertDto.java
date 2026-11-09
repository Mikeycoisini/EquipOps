package com.equipops.domain.dto.inventory;

import java.math.BigDecimal;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 保存备件请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SparePartUpsertDto {

    /** 备件编号 */
    @NotBlank(message = "备件编号不能为空")
    @Size(max = 64, message = "备件编号不能超过64个字符")
    private String partCode;

    /** 备件名称 */
    @NotBlank(message = "备件名称不能为空")
    @Size(max = 160, message = "备件名称不能超过160个字符")
    private String name;

    /** 规格型号 */
    @Size(max = 160, message = "规格不能超过160个字符")
    private String spec;

    /** 分类 */
    @Size(max = 100, message = "分类不能超过100个字符")
    private String category;

    /** 所在位置或库位 */
    @Size(max = 120, message = "库位不能超过120个字符")
    private String location;

    /** 兼容旧字段定义；目录写入禁止传入，库存只能通过库存业务变更。 */
    @Null(message = "目录接口不允许设置库存数量，请使用库存业务接口")
    private BigDecimal stockQuantity;

    /** 安全库存数量 */
    @DecimalMin(value = "0", message = "安全库存不能小于0")
    @Digits(integer = 11, fraction = 3, message = "安全库存最多11位整数、3位小数")
    private BigDecimal minStockQuantity;

    /** 计量单位 */
    @Size(max = 24, message = "计量单位不能超过24个字符")
    private String unit;

    /** 是否启用 */
    private Boolean enabled;
}
