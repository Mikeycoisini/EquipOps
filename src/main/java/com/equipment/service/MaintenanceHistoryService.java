package com.equipment.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.vo.WorkOrderVO;

public interface MaintenanceHistoryService {
    IPage<WorkOrderVO> getMaintenanceHistory(Long equipmentId, long current, long size);

}
