package com.equipops.domain.entity;

import com.equipops.domain.enums.InventoryReferenceType;
import com.equipops.domain.enums.InventoryTransactionType;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 库存流水。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity @Table(name = "inventory_transactions")
@TableName("inventory_transactions")
public class InventoryTransactionEntity {
    /** 库存流水 ID */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属企业 ID，由服务端当前企业上下文确定。 */
    @Column(name = "tenant_id")
    @TableField(value = "tenant_id", updateStrategy = FieldStrategy.NEVER)
    private Long tenantId;

    /** 流水所属仓库 ID；旧流水需经核对后回填。 */
    @Column(name = "warehouse_id")
    @TableField("warehouse_id")
    private Long warehouseId;

    /** 客户端业务请求号；旧流水可为空，当前企业内唯一。 */
    @Column(name = "request_id", length = 64)
    @TableField(value = "request_id", updateStrategy = FieldStrategy.NEVER)
    private String requestId;

    /** 退料关联的原领料流水 ID；普通入库、领料及未核对旧流水为空。 */
    @Column(name = "issue_transaction_id")
    @TableField(value = "issue_transaction_id", updateStrategy = FieldStrategy.NEVER)
    private Long issueTransactionId;

    /** 库存流水编号 */
    @Column(name = "transaction_no", nullable = false, length = 40, unique = true)
    private String transactionNo;

    /** 关联备件 ID */
    @Column(name = "spare_part_id", nullable = false)
    private Long sparePartId;

    /** 库存交易类型 */
    @Enumerated(EnumType.STRING) @Column(name = "transaction_type", nullable = false, length = 16)
    private InventoryTransactionType transactionType;

    /** 本次变动数量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    /** 关联单据类型 */
    @Enumerated(EnumType.STRING) @Column(name = "reference_type", nullable = false, length = 24)
    private InventoryReferenceType referenceType = InventoryReferenceType.MANUAL;

    /** 关联单据编号或说明 */
    @Column(name = "reference_text", length = 160)
    private String referenceText;

    /** 关联工单 ID */
    @Column(name = "work_order_id")
    private Long workOrderId;

    /** 创建人用户 ID */
    @Column(name = "created_by")
    private Long createdBy;

    /** 备注 */
    @Column(length = 500)
    private String note;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
