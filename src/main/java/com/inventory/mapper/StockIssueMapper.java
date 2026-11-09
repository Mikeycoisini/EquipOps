package com.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.equipops.domain.entity.SparePartEntity;
import com.equipops.domain.entity.InventoryTransactionEntity;
import java.math.BigDecimal;
import com.equipops.domain.vo.StockInWarehouseCheckVO;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockIssueMapper extends BaseMapper<SparePartEntity> {

    StockInWarehouseCheckVO lockWarehouseForIssue(
            @Param("tenantId") Long tenantId,
            @Param("warehouseId") Long warehouseId);

    /** 查询并锁定当前企业备件的启用状态。 */
    Boolean lockSparePartEnabled(
            @Param("tenantId") Long tenantId,
            @Param("sparePartId") Long sparePartId);

    /** 插入工单出库流水，回填 log.id；请求号由数据库唯一键保护。 */
    int insertStockIssue(@Param("log") InventoryTransactionEntity log);

    /** 并发请求号冲突后，按企业及请求号当前读原流水。 */
    InventoryTransactionEntity findByRequestForUpdate(@Param("tenantId") Long tenantId,
                                                     @Param("requestId") String requestId);

    /** 余额足够时原子扣减，返回实际影响行数；不创建虚假零余额。 */
    int decreaseStock(@Param("tenantId") Long tenantId, @Param("warehouseId") Long warehouseId,
                      @Param("sparePartId") Long sparePartId, @Param("quantity") BigDecimal quantity);

    /** 首次领料建立用料行，已有记录保留所有数量。 */
    int ensureWorkOrderPartRow(@Param("tenantId") Long tenantId, @Param("workOrderId") Long workOrderId,
                               @Param("sparePartId") Long sparePartId);

    /** 在数值上限内累计工单已领量，不改变计划量或退回量。 */
    int increaseIssuedQuantity(@Param("tenantId") Long tenantId, @Param("workOrderId") Long workOrderId,
                                @Param("sparePartId") Long sparePartId, @Param("quantity") BigDecimal quantity);

    /** 按企业和流水主键重查保存结果。 */
    InventoryTransactionEntity findById(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
