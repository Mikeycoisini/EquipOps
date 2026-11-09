package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.equipops.domain.enums.InventoryExportStatus;
import com.equipops.domain.enums.InventoryReferenceType;
import com.equipops.domain.enums.InventoryTransactionType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 库存流水Excel导出任务；本步只准备MyBatis映射，不触发未建表的JPA校验。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("inventory_export_tasks")
public class InventoryExportTaskEntity {
    /** 导出任务主键，由数据库生成。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务所属企业，由可信上下文确定，创建后不改。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long tenantId;

    /** 任务创建人用户ID，由可信上下文确定，创建后不改。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long createdBy;

    /** 提交时固定的筛选仓库ID；为空时不限制仓库。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long warehouseId;

    /** 提交时固定的筛选备件ID；为空时不限制备件。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long sparePartId;

    /** 提交时固定的筛选工单ID；为空时不限制工单。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long workOrderId;

    /** 提交时固定的筛选流水类型，沿用INBOUND、OUTBOUND、ADJUSTMENT。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private InventoryTransactionType transactionType;

    /** 提交时固定的筛选业务来源类型，沿用现有枚举。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private InventoryReferenceType referenceType;

    /** 提交时固定的筛选开始时间，包含该时刻；为空时不限制下界。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime startTime;

    /** 提交时固定的筛选结束时间，包含该时刻；为空时不限制上界。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime endTime;

    /** 执行开始时记录的流水ID上界；不代表严格数据库快照。 */
    private Long upperBoundId;

    /** 任务当前状态，由后续业务控制状态流转。 */
    private InventoryExportStatus status;

    /** 已写入的数据行数，不包括表头；由执行过程维护。 */
    private Long processedRows;

    /** 受控文件根目录下的相对存储键，仅供服务端使用。 */
    private String storageKey;

    /** 下载时展示的Excel文件名，不作为客户端指定的文件路径。 */
    private String fileName;

    /** 完成后的Excel文件大小，单位字节；未生成时为空。 */
    private Long fileSize;

    /** 失败原因编码，如TIMEOUT、WORKER_LOST；不保存异常堆栈。 */
    private String errorCode;

    /** 可公开的失败原因提示，不含密码、SQL或绝对路径。 */
    private String errorMessage;

    /** 接收并执行任务的实例标识，仅供服务端使用。 */
    private String ownerInstanceId;

    /** 本次执行的唯一令牌，配合状态与租约防止旧线程发布。 */
    private String runToken;

    /** 执行租约截止时间，后续须按数据库时间校验与续期。 */
    private LocalDateTime leaseUntil;

    /** 任务创建时间，预留由数据库生成。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 任务最后更新时间，预留由数据库维护。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    /** 实际开始执行时间，排队时为空。 */
    private LocalDateTime startedAt;

    /** 执行结束时间，由业务在成功、失败、取消或拒绝时记录。 */
    private LocalDateTime finishedAt;

    /** 执行超时截止时间；开始执行时确定，不是文件过期时间。 */
    private LocalDateTime deadlineAt;

    /** 成功文件的下载有效期截止时间，未成功时可为空。 */
    private LocalDateTime expiresAt;
}
