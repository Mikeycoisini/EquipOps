package com.equipops.domain.dto.inventory;

import com.equipops.domain.enums.InventoryReferenceType;
import com.equipops.domain.enums.InventoryTransactionType;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 登记库存流水请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTransactionCreateDto {

    /** 关联备件 ID */
    private Long sparePartId;

    /** 库存交易类型 */
    private InventoryTransactionType transactionType;

    /** 本次变动数量 */
    private BigDecimal quantity;

    /** 关联单据类型 */
    private InventoryReferenceType referenceType;

    /** 关联单据编号或说明 */
    private String referenceText;

    /** 关联工单 ID */
    private Long workOrderId;

    /** 创建人用户 ID */
    private Long createdBy;

    /** 备注 */
    private String note;
}
