package com.equipops.domain.dto.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 修改一条已有仓库余额的安全库存阈值，不接收实际数量、企业或操作人。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseStockMinQuantityUpdateDto {
    /** 安全库存阈值，允许为0；实际数量严格小于此值时才判定为低库存。 */
    @NotNull(message = "安全库存阈值不能为空")
    @DecimalMin(value = "0", message = "安全库存阈值不能小于0")
    @Digits(integer = 11, fraction = 3, message = "安全库存阈值最多11位整数和3位小数")
    private BigDecimal minStockQuantity;
}