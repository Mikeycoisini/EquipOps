package com.workorder.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.vo.WorkOrderVO;

/** 设备档案中关联工单的查询契约。 */
public interface EquipmentWorkOrderServer {
    /** 分页读取当前企业指定设备的关联工单。 */
    IPage<WorkOrderVO> getWorkOrderPage(Long equipmentId, long current, long size);
}