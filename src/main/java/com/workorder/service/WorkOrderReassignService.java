package com.workorder.service;
import com.equipops.domain.dto.workorder.WorkOrderReassignDto;
import com.equipops.domain.vo.WorkOrderVO;
/** 新增转派入口，保留原派单和状态动作协议。 */
public interface WorkOrderReassignService {
 WorkOrderVO reassign(Long id, WorkOrderReassignDto request);
}
