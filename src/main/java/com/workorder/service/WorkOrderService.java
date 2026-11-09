package com.workorder.service;

import com.equipops.domain.dto.workorder.*;
import com.equipops.domain.vo.WorkOrderVO;
import com.equipops.domain.vo.WorkOrderLogVO;
import com.equipops.domain.vo.WorkOrderPartVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;

import java.util.List;

/** 工单业务服务契约。 */
public interface WorkOrderService {

    /** 提交报修并创建维修工单。 */
    WorkOrderVO reportRepair(WorkOrderCreateDto request);

    /** 将维修工单派发给指定负责人。 */
    WorkOrderVO dispatch(Long id, WorkOrderDispatchDto request);
    /** 分页查询当前企业工单，不在列表中逐条加载日志。 */
    IPage<WorkOrderVO> getWorkOrderPage(WorkOrderPageQueryDto query);

    /** 校验当前企业范围后，查询工单、设备、用料与时间线。 */
    WorkOrderVO getWorkOrderDetail(Long id);

    /** 查询当前企业工单的用料；未知或其他企业的工单返回 404。 */
    List<WorkOrderPartVO> getWorkOrderParts(Long id);

    /** 校验工单归属后，按发生时间查询该工单日志。 */
    List<WorkOrderLogVO> getWorkOrderLogs(Long id);

    WorkOrderVO startRepair(Long id);

    WorkOrderLogVO addRepairNoteLog(Long id, @Valid RepairNoteCreateDto request);

    WorkOrderVO submitAcceptance(Long id);

    WorkOrderVO approve(Long id);

    WorkOrderVO reject(Long id, @Valid WorkOrderRejectDto request);
}
