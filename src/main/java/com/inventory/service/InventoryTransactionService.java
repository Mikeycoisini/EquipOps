package com.inventory.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.inventory.InventoryTransactionPageQueryDto;
import com.equipops.domain.vo.InventoryTransactionVO;

/** 库存流水只读业务；本次不实现库存写入。 */
public interface InventoryTransactionService {
    /** 分页查询当前企业可见的库存流水。 */
    IPage<InventoryTransactionVO> getTransactionPage(InventoryTransactionPageQueryDto query);

    /** 查询当前企业流水详情；不存在或属于其他企业的流水按404处理。 */
    InventoryTransactionVO getTransactionDetail(Long id);
}
