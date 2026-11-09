package com.equipops.domain.dto.inventory;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.*;
import com.equipops.domain.enums.InventoryTransactionType;
import com.equipops.domain.enums.InventoryReferenceType;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/** 列表查询条件；企业范围从登录上下文取得，不接收客户端 tenantId。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTransactionPageQueryDto {
    /** 当前页码，小于1时按第1页处理。 */
    private long current = 1;

    /** 每页条数，业务层限制为1到100。 */
    private long size = 10;

    /** 所属仓库 ID，仅能查询当前企业仓库。 */
    @Positive(message = "仓库 ID 必须大于0")
    private Long warehouseId;

    /** 关联备件 ID，仅能查询当前企业备件。 */
    @Positive(message = "备件 ID 必须大于0")
    private Long sparePartId;

    /** 关联工单 ID，仅能查询当前企业工单。 */
    @Positive(message = "工单 ID 必须大于0")
    private Long workOrderId;

    /** 库存交易类型，沿用现有 INBOUND、OUTBOUND、ADJUSTMENT。 */
    private InventoryTransactionType transactionType;

    /** 关联单据类型。 */
    private InventoryReferenceType referenceType;

    /** 查询开始时间，包含该时刻；格式为 yyyy-MM-dd'T'HH:mm:ss。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    /** 查询结束时间，包含该时刻，不能早于开始时间。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
}
