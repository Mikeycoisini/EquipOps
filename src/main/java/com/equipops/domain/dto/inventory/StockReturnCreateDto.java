package com.equipops.domain.dto.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工单退料请求；仓库、备件及工单从原领料流水取得，企业及操作人从登录上下文取得。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockReturnCreateDto {

    /** 原工单领料的出库流水 ID，用于确定退回对象及原仓库。 */
    @NotNull(message = "原领料流水 ID 不能为空")
    @Positive(message = "原领料流水 ID 必须大于0")
    private Long issueTransactionId;

    /** 本次退回数量；累计退回不能超过原领用数量，该限制由业务层检查。 */
    @NotNull(message = "退料数量不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "退料数量必须大于0")
    @Digits(integer = 11, fraction = 3, message = "退料数量最多11位整数和3位小数")
    private BigDecimal quantity;

    /** 本次退料备注，可不填。 */
    @Size(max = 500, message = "备注不能超过500个字符")
    private String note;
}
