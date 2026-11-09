package com.equipops.domain.vo;

import com.equipops.domain.enums.InventoryExportStatus;
import com.equipops.domain.enums.InventoryReferenceType;
import com.equipops.domain.enums.InventoryTransactionType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 导出任务公开资料；不包含内部存储键、实例标识、执行令牌或租约。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryExportTaskVO {
    /** 导出任务ID，查询及下载接口使用此ID。 */
    private Long id;

    /** 筛选仓库ID；为空时不限制仓库。 */
    private Long warehouseId;

    /** 筛选备件ID；为空时不限制备件。 */
    private Long sparePartId;

    /** 筛选工单ID；为空时不限制工单。 */
    private Long workOrderId;

    /** 筛选流水类型，沿用INBOUND、OUTBOUND、ADJUSTMENT。 */
    private InventoryTransactionType transactionType;

    /** 筛选业务来源类型，沿用现有枚举。 */
    private InventoryReferenceType referenceType;

    /** 筛选开始时间，包含该时刻；为空时不限制下界。 */
    private LocalDateTime startTime;

    /** 筛选结束时间，包含该时刻；为空时不限制上界。 */
    private LocalDateTime endTime;

    /** 任务当前状态。 */
    private InventoryExportStatus status;

    /** 已写入的数据行数，不包括表头，不代表百分比。 */
    private Long processedRows;

    /** 下载时展示的Excel文件名；不返回服务器存储位置。 */
    private String fileName;

    /** Excel文件大小，单位字节，文件未生成时为空。 */
    private Long fileSize;

    /** 可公开的错误编码，正常任务可为空。 */
    private String errorCode;

    /** 可公开的失败原因，不返回内部异常或SQL。 */
    private String errorMessage;

    /** 任务创建时间。 */
    private LocalDateTime createdAt;

    /** 实际开始执行时间，排队时为空。 */
    private LocalDateTime startedAt;

    /** 任务执行结束时间，未结束时为空。 */
    private LocalDateTime finishedAt;

    /** 成功文件的下载有效期截止时间。 */
    private LocalDateTime expiresAt;
}
