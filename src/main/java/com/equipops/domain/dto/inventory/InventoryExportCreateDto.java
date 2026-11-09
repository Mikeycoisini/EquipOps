package com.equipops.domain.dto.inventory;

import com.equipops.domain.enums.InventoryReferenceType;
import com.equipops.domain.enums.InventoryTransactionType;
import java.time.LocalDateTime;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 创建Excel导出的筛选条件；企业和创建人取服务端上下文，不包含分页参数。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryExportCreateDto {
    /** 筛选仓库ID；为空时不限制仓库。 */
    @Positive(message = "仓库ID必须大于0")
    private Long warehouseId;

    /** 筛选备件ID；为空时不限制备件。 */
    @Positive(message = "备件ID必须大于0")
    private Long sparePartId;

    /** 筛选工单ID；为空时不限制工单。 */
    @Positive(message = "工单ID必须大于0")
    private Long workOrderId;

    /** 筛选流水类型，沿用INBOUND、OUTBOUND、ADJUSTMENT。 */
    private InventoryTransactionType transactionType;

    /** 筛选业务来源类型，沿用现有枚举。 */
    private InventoryReferenceType referenceType;

    /** 筛选开始时间，包含该时刻；为空时不限制下界。 JSON使用yyyy-MM-dd'T'HH:mm:ss，起止顺序由后续业务校验。 */
    private LocalDateTime startTime;

    /** 筛选结束时间，包含该时刻；为空时不限制上界。 JSON使用yyyy-MM-dd'T'HH:mm:ss，起止顺序由后续业务校验。 */
    private LocalDateTime endTime;
}
