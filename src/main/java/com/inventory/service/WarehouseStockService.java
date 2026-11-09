package com.inventory.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.inventory.WarehouseStockPageQueryDto;
import com.equipops.domain.dto.inventory.WarehouseStockMinQuantityUpdateDto;
import com.equipops.domain.vo.WarehouseStockVO;

/** 仓库余额查询与安全库存阈值维护契约，不处理入库、领料和退料。 */
public interface WarehouseStockService {
    /** 校验当前企业和筛选条件后，分页返回已建账余额。 */
    IPage<WarehouseStockVO> getStockPage(WarehouseStockPageQueryDto query);

    /** 修改当前企业已有库存余额的安全库存阈值，返回更新后的余额视图。 */
    WarehouseStockVO updateMinStockQuantity(Long id, WarehouseStockMinQuantityUpdateDto request);
}
