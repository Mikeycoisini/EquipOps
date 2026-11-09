package com.inventory.mapper;

import com.equipops.domain.entity.InventoryTransactionEntity;
import com.equipops.domain.vo.StockInWarehouseCheckVO;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 入库专用 SQL 位于 XML，DTO 不作为数据库实体使用。 */
@Mapper
public interface StockInMapper {
    /** 读取当前企业已成功的请求流水，供普通重试快速返回。 */
    InventoryTransactionEntity findByRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
    /** 唯一键冲突后使用当前读，取得并发事务最新提交的流水。 */
    InventoryTransactionEntity findByRequestForUpdate(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
    /** 查询并锁定同企业仓库和厂区，状态检查之后保持锁至事务结束。 */
    StockInWarehouseCheckVO lockWarehouseForStockIn(@Param("tenantId") Long tenantId, @Param("warehouseId") Long warehouseId);
    /** 查询并锁定当前企业备件的启用状态，不存在时返回 null。 */
    Boolean lockSparePartEnabled(@Param("tenantId") Long tenantId, @Param("sparePartId") Long sparePartId);
    /** 插入可信入库流水，请求号由唯一键保护，数据库主键回填到 log.id。 */
    int insertStockIn(@Param("log") InventoryTransactionEntity log);
    /** 首次入库建立零余额；已有记录保留数量及安全库存。 */
    int ensureStockRow(@Param("tenantId") Long tenantId, @Param("warehouseId") Long warehouseId, @Param("sparePartId") Long sparePartId);
    /** 数据库原子加量并限制数值上限，返回实际影响行数。 */
    int increaseStock(@Param("tenantId") Long tenantId, @Param("warehouseId") Long warehouseId,
                      @Param("sparePartId") Long sparePartId, @Param("quantity") BigDecimal quantity);
    /** 按企业及主键重查保存的流水，取得数据库生成的时间。 */
    InventoryTransactionEntity findById(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
