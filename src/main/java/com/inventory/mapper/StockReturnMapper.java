package com.inventory.mapper;

import com.equipops.domain.entity.InventoryTransactionEntity;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.vo.StockInWarehouseCheckVO;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 退料专用SQL，所有查询和更新均显式限定当前企业。 */
@Mapper
public interface StockReturnMapper {
    /** 查询当前企业的原领料流水。 */
    InventoryTransactionEntity selectNowTenant(@Param("tenantId") Long tenantId,
                                               @Param("issueTransactionId") Long issueTransactionId);
    /** 读取已成功请求供普通重试使用，不提前锁不存在的请求号。 */
    InventoryTransactionEntity findByRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
    /** 唯一键冲突后当前读已成功流水。 */
    InventoryTransactionEntity findByRequestForUpdate(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
    /** 与入库及领料保持相同顺序，锁定仓库及所属厂区。 */
    StockInWarehouseCheckVO lockWarehouseForReturn(@Param("tenantId") Long tenantId, @Param("warehouseId") Long warehouseId);
    /** 锁定备件的启用状态；null表示当前企业没有该备件。 */
    Boolean lockSparePartEnabled(@Param("tenantId") Long tenantId, @Param("sparePartId") Long sparePartId);
    /** 锁定工单，用于检查当前负责人及允许退料的状态。 */
    WorkOrderEntity lockWorkOrderForReturn(@Param("tenantId") Long tenantId, @Param("workOrderId") Long workOrderId);
    /** 锁定原领料，串行核对同一原领料的累计退回量。 */
    InventoryTransactionEntity lockIssueForReturn(@Param("tenantId") Long tenantId,
                                                 @Param("issueTransactionId") Long issueTransactionId);
    /** INSERT SELECT从原领料取得归属，SQL生成流水编号，并回填log.id。 */
    int insertStockReturn(@Param("log") InventoryTransactionEntity log);
    /** 当前读所有关联退料的数量，包括本事务新插入的记录，不使用普通快照SUM。 */
    List<BigDecimal> selectReturnedQuantitiesForUpdate(@Param("tenantId") Long tenantId,
                                                      @Param("issueTransactionId") Long issueTransactionId);
    /** 原子增加原仓库余额并限制DECIMAL上限，不在缺失时创建余额。 */
    int increaseStock(@Param("tenantId") Long tenantId, @Param("warehouseId") Long warehouseId,
                      @Param("sparePartId") Long sparePartId, @Param("quantity") BigDecimal quantity);
    /** 只累计工单已退量，要求同企业且不超过工单已领量。 */
    int increaseReturnedQuantity(@Param("tenantId") Long tenantId, @Param("workOrderId") Long workOrderId,
                                 @Param("sparePartId") Long sparePartId, @Param("quantity") BigDecimal quantity);
    /** 读取保存后的退料流水，返回数据库时间。 */
    InventoryTransactionEntity findById(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
