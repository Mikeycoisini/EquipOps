package com.inventory.service;

import com.equipops.domain.dto.inventory.StockIssueCreateDto;
import com.equipops.domain.vo.InventoryTransactionVO;
import jakarta.validation.Valid;

public interface StockIssueService {
    InventoryTransactionVO issue(String requestId, @Valid StockIssueCreateDto request);
}
