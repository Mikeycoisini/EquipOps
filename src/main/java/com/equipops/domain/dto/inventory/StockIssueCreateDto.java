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

/** 工单领料请求；企业与操作人从登录上下文取得。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockIssueCreateDto {

    /** 本次领料关联的维修工单 ID。 */
    @NotNull(message = "工单 ID 不能为空")
    @Positive(message = "工单 ID 必须大于0")
    private Long workOrderId;

    /** 发出备件的仓库 ID。 */
    @NotNull(message = "仓库 ID 不能为空")
    @Positive(message = "仓库 ID 必须大于0")
    private Long warehouseId;

    /** 本次领用的备件 ID。 */
    @NotNull(message = "备件 ID 不能为空")
    @Positive(message = "备件 ID 必须大于0")
    private Long sparePartId;

    /** 本次领用数量，必须大于0，最多11位整数和3位小数。 */
    @NotNull(message = "领料数量不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "领料数量必须大于0")
    @Digits(integer = 11, fraction = 3, message = "领料数量最多11位整数和3位小数")
    private BigDecimal quantity;

    /** 本次领料备注，可不填。 */
    @Size(max = 500, message = "备注不能超过500个字符")
    private String note;
}
