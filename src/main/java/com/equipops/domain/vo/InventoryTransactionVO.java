package com.equipops.domain.vo;

import com.equipops.domain.enums.InventoryReferenceType;
import com.equipops.domain.enums.InventoryTransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 库存流水。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTransactionVO {

    /** 库存流水 ID */
    private Long id;

    /** 所属企业 ID。 */
    private Long tenantId;

    /** 所属仓库 ID；尚未核对的旧流水允许为空。 */
    private Long warehouseId;


    /** 客户端业务请求号；旧流水可为空，当前企业内唯一。 */
    private String requestId;

    /** 退料关联的原领料流水 ID；普通入库、领料及未核对旧流水为空。 */
    private Long issueTransactionId;

    /** 库存流水编号 */
    private String transactionNo;

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

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 关联仓库编号，查询时读取当前资料；旧流水无关联时为空。 */
    private String warehouseCode;

    /** 关联仓库名称，查询时读取当前资料，不是历史名称快照。 */
    private String warehouseName;

    /** 关联备件编号，查询时读取当前资料；无法关联时为空。 */
    private String partCode;

    /** 关联备件名称，查询时读取当前资料，不是历史名称快照。 */
    private String partName;

    /** 关联工单编号；手工入库等未关联工单的流水为空。 */
    private String workOrderNo;
}
