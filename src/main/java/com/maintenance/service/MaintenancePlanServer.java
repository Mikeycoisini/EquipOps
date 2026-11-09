package com.maintenance.service;

import com.equipops.domain.dto.maintenance.MaintenancePlanCreateDto;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.equipops.domain.dto.maintenance.MaintenanceExecutionPageQueryDto;
import com.equipops.domain.dto.maintenance.MaintenanceExecutionCreateDto;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import com.equipops.domain.dto.maintenance.MaintenancePlanUpdateDto;
import com.equipops.domain.dto.maintenance.MaintenancePlanEnabledUpdateDto;
import com.equipops.domain.dto.maintenance.MaintenancePlanPageQueryDto;
import com.baomidou.mybatisplus.core.metadata.IPage;

/** 维护与巡检计划业务接口。 */
public interface MaintenancePlanServer {

    /** 为当前企业创建设备维护或巡检计划，返回完整的计划资料。 */
    MaintenancePlanVO createPlan(MaintenancePlanCreateDto request);
    /** 当前企业有效成员可分页查看本企业计划，附带设备及负责人姓名。 */
    IPage<MaintenancePlanVO> getPlanPage(MaintenancePlanPageQueryDto query);

    /** 有效企业成员查看计划详情；不存在或不属于当前企业时返回404。 */
    MaintenancePlanVO getPlanDetail(Long id);

    /** 当前企业管理员修改启用状态，不删除计划或改变周期日期。 */
    void updatePlanEnabled(Long id, MaintenancePlanEnabledUpdateDto request);

    /** 修改当前企业计划，保留创建资料和启用状态；允许保留原逾期日期。 */
    MaintenancePlanVO updatePlan(Long id, MaintenancePlanUpdateDto request);

    /** 负责人登记已到期计划的执行结果；请求号用于幂等重试。 */
    MaintenanceExecutionVO createExecution(Long planId, String requestId, MaintenanceExecutionCreateDto request);

    /** 分页查看本企业指定计划的执行记录，返回登记时的历史快照。 */
    IPage<MaintenanceExecutionVO> getExecutionPage(Long planId, MaintenanceExecutionPageQueryDto query);

    /** 有效成员查看本企业执行记录详情，返回历史快照。 */
    MaintenanceExecutionVO getExecutionDetail(Long id);

}
