package com.inventory.service;

import com.equipops.domain.dto.inventory.StockReturnCreateDto;
import com.equipops.domain.vo.InventoryTransactionVO;

/** 工单退料业务契约，企业和操作人由实现层从可信上下文获取。 */
public interface StockReturnService {

    /** 根据原领料流水退回备件，相同请求不得重复增加库存。 */
    InventoryTransactionVO returnStock(String requestId, StockReturnCreateDto request);
}
