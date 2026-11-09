package com.maintenance.mapper;

import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.entity.MaintenanceExecutionEntity;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import java.time.LocalDate;
import com.equipops.domain.dto.maintenance.MaintenancePlanUpdateDto;
import com.equipops.domain.dto.maintenance.MaintenancePlanPageQueryDto;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.entity.MaintenancePlanEntity;
import com.equipops.domain.entity.SiteEntity;
import com.equipops.domain.vo.MaintenancePlanVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 维护计划数据访问；查询和写入SQL集中放在对应XML中。 */
@Mapper
public interface MaintenancePlanMapper {
    /** 按当前企业和设备ID查询，防止使用其他企业的设备。 */
    EquipmentEntity selectTenantEquipment(@Param("tenantId") Long tenantId,
                                          @Param("equipmentId") Long equipmentId);

    /** 查询设备所属的本企业厂区，供业务层检查启用状态。 */
    SiteEntity selectTenantSite(@Param("tenantId") Long tenantId, @Param("siteId") Long siteId);

    /** 负责人须同时满足本企业成员启用和全局账号启用，不要求管理员角色。 */
    int countEnabledAssignee(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    /** 插入一条计划；数据库生成的主键回填到plan.id。 */
    int insertPlan(@Param("plan") MaintenancePlanEntity plan);

    /** 按企业和计划主键回查，附带当前设备和负责人名称。 */
    MaintenancePlanVO selectPlanDetail(@Param("tenantId") Long tenantId, @Param("id") Long id);
    /** 按可信企业范围和可选条件分页；复用计划详情的VO映射。 */
    IPage<MaintenancePlanVO> selectPlanPage(Page<MaintenancePlanVO> page,
                                           @Param("tenantId") Long tenantId,
                                           @Param("query") MaintenancePlanPageQueryDto query);

    /** 当前读并锁定本企业计划；enabled非空，返回null代表没有这条记录。 */
    Boolean selectPlanEnabledForUpdate(@Param("tenantId") Long tenantId, @Param("id") Long id);

    /** 仅修改指定企业计划的启用状态。 */
    int updatePlanEnabled(@Param("tenantId") Long tenantId, @Param("id") Long id,
                          @Param("enabled") Boolean enabled);

    /** 当前读并锁定计划，供编辑校验和更新后回查使用。 */
    MaintenancePlanVO selectPlanDetailForUpdate(@Param("tenantId") Long tenantId, @Param("id") Long id);

    /** 只更新可编辑字段；厂区来自已校验设备，不允许请求修改创建资料和启用状态。 */
    int updatePlan(@Param("tenantId") Long tenantId, @Param("id") Long id,
                   @Param("request") MaintenancePlanUpdateDto request, @Param("siteId") Long siteId);

    /** 编辑专用当前读：锁定设备并读取最新厂区归属。 */
    EquipmentEntity selectTenantEquipmentForUpdate(@Param("tenantId") Long tenantId, @Param("equipmentId") Long equipmentId);

    /** 编辑专用当前读：锁定设备所属厂区并读取最新启用状态。 */
    SiteEntity selectTenantSiteForUpdate(@Param("tenantId") Long tenantId, @Param("siteId") Long siteId);

    /** 当前读并锁定启用成员及账号；返回用户ID，不可用时为null。 */
    Long selectEnabledAssigneeForUpdate(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    /** 请求号查询，必须在执行方法取得计划锁后调用。 */
    MaintenanceExecutionEntity findExecutionByRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
    /** 本期是否已经有完成记录。 */
    int countExecutionForPeriod(@Param("tenantId") Long tenantId, @Param("planId") Long planId, @Param("scheduledDate") LocalDate scheduledDate);
    /** 写入历史快照，回填execution.id。 */
    int insertExecution(@Param("execution") MaintenanceExecutionEntity execution);
    /** 只在当前启用状态及原到期日仍匹配时推进计划。 */
    int advancePlanDueDate(@Param("tenantId") Long tenantId, @Param("planId") Long planId,
                           @Param("expectedDueDate") LocalDate expectedDueDate, @Param("nextDueDate") LocalDate nextDueDate);
    /** 读取本企业执行记录，不联查可变的计划资料。 */
    MaintenanceExecutionVO selectExecutionDetail(@Param("tenantId") Long tenantId, @Param("id") Long id);

    /** 执行登记锁后检查：当前企业、账号及成员均须启用，查询同时刷新缓存。 */
    Long selectEnabledExecutorForUpdate(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    /** 本企业指定计划的历史分页；名称等字段直接使用执行记录快照。 */
    IPage<MaintenanceExecutionVO> selectExecutionPage(Page<MaintenanceExecutionVO> page,
            @Param("tenantId") Long tenantId, @Param("planId") Long planId);

    /** 到期待办分页；today由业务层确定，保留原分页方法与已有调用。 */
    IPage<MaintenancePlanVO> selectDuePlanPage(Page<MaintenancePlanVO> page,
            @Param("tenantId") Long tenantId, @Param("query") MaintenancePlanPageQueryDto query,
            @Param("today") LocalDate today);

}
