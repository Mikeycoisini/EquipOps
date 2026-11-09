package com.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.inventory.WarehouseStockPageQueryDto;
import com.equipops.domain.entity.WarehouseStockEntity;
import com.equipops.domain.vo.WarehouseStockVO;
import com.equipops.domain.vo.StockInWarehouseCheckVO;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

/** 库存查询 SQL 位于 XML；每条查询都必须显式限定企业。 */
@Mapper
public interface WarehouseStockMapper extends BaseMapper<WarehouseStockEntity> {
    /** 校验仓库是否在当前企业可见，不泄露其他企业资料。 */
    long countTenantWarehouse(@Param("tenantId") Long tenantId, @Param("warehouseId") Long warehouseId);
    /** 校验备件是否在当前企业可见，停用备件仍可查询历史余额。 */
    long countTenantSparePart(@Param("tenantId") Long tenantId, @Param("sparePartId") Long sparePartId);
    /** 分页联查余额、仓库和备件；keyword 由 Service 去除首尾空白。 */
    IPage<WarehouseStockVO> selectStockPage(Page<WarehouseStockVO> page,
            @Param("tenantId") Long tenantId,
            @Param("query") WarehouseStockPageQueryDto query,
            @Param("keyword") String keyword);

    /** 查询当前企业仓库及所属厂区状态，供入库校验。 */
    StockInWarehouseCheckVO selectWarehouseForStockIn(
            @Param("tenantId") Long tenantId,
            @Param("warehouseId") Long warehouseId);

    /** 查询当前企业备件的启用状态；没有记录时返回 null。 */
    Boolean selectSparePartEnabled(
            @Param("tenantId") Long tenantId,
            @Param("sparePartId") Long sparePartId);

    /** 统计当前企业已建账余额中的低库存记录数。 */
    long countLowStock(@Param("tenantId") Long tenantId);

    int updateMinStockQuantity(
            @Param("tenantId") Long tenantId,
            @Param("id") Long id,
            @Param("minStockQuantity") BigDecimal minStockQuantity
    );

    /** 返回本企业指定余额的完整关联资料和低库存状态。 */
    WarehouseStockVO selectStockDetail(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
