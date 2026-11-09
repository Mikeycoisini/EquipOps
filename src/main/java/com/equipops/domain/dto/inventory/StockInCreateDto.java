package com.equipops.domain.dto.inventory;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 备件入库请求；企业与操作人从登录上下文取得。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockInCreateDto {

    /** 接收入库备件的仓库 ID。 */
    @NotNull(message = "仓库 ID 不能为空")
    @Positive(message = "仓库 ID 必须大于0")
    private Long warehouseId;

    /** 入库备件 ID。 */
    @NotNull(message = "备件 ID 不能为空")
    @Positive(message = "备件 ID 必须大于0")
    private Long sparePartId;

    /** 入库数量，必须大于0，最多11位整数和3位小数。 */
    @NotNull(message = "入库数量不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "入库数量必须大于0")
    @Digits(integer = 11, fraction = 3, message = "入库数量最多11位整数和3位小数")
    private BigDecimal quantity;

    /** 入库关联单据编号或说明，可不填。 */
    @Size(max = 160, message = "关联单据说明不能超过160个字符")
    private String referenceText;

    /** 本次入库备注，可不填。 */
    @Size(max = 500, message = "备注不能超过500个字符")
    private String note;
}