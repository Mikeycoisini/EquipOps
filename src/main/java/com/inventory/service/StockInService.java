package com.inventory.service;

import com.equipops.domain.dto.inventory.StockInCreateDto;
import com.equipops.domain.vo.InventoryTransactionVO;
import jakarta.validation.Valid;

public interface StockInService {
    InventoryTransactionVO stocktoIn(String requestId, @Valid StockInCreateDto request);
}
