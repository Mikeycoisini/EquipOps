package com.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.entity.WorkOrderLogEntity;
import com.equipops.domain.vo.EquipmentVO;
import com.equipops.domain.vo.WorkOrderPartVO;
import com.equipops.domain.vo.WorkspaceSummaryVO;
import java.util.List;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.workorder.WorkOrderPageQueryDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrderEntity> {
    /** 分页查询当前企业工单；keyword 已由业务层去除首尾空白。 */
    IPage<WorkOrderEntity> selectWorkOrderPage(Page<WorkOrderEntity> page,
                                             @Param("tenantId") Long tenantId,
                                             @Param("query") WorkOrderPageQueryDto query,
                                             @Param("keyword") String keyword);

    /** 查询当前企业工单关联的同企业、同厂区设备。 */
    EquipmentVO selectWorkOrderEquipment(@Param("tenantId") Long tenantId,
                                         @Param("workOrderId") Long workOrderId);

    /** 联表查询当前企业工单用料，备件也必须属于同一企业。 */
    List<WorkOrderPartVO> selectWorkOrderParts(@Param("tenantId") Long tenantId,
                                             @Param("workOrderId") Long workOrderId);

    /** 一次汇总当前企业的工单状态数量，不读取其他企业或未归属旧数据。 */
    WorkspaceSummaryVO selectWorkspaceSummary(@Param("tenantId") Long tenantId);

    /** 只有待处理且尚未派单的工单才能更新负责人，返回实际影响行数。 */
    int assignWorkOrder(@Param("tenantId") Long tenantId,
                        @Param("id") Long id,
                        @Param("assigneeUserId") Long assigneeUserId);

    /** 查询并锁定当前企业的目标工单，供追加维修记录时校验状态和负责人。 */
    WorkOrderEntity selectTenantAnduserId(@Param("tenantId") Long tenantId,
                                         @Param("id") Long id);

    /** 新增维修处理记录，生成的日志 ID 回填到 workLog.id。 */
    int insertWorkLog(@Param("log") WorkOrderLogEntity workLog);

    /** 只有待处理且负责人是当前用户的工单才能开始维修，返回实际影响行数。 */
    int startRepair(@Param("tenantId") Long tenantId,
                    @Param("id") Long id,
                    @Param("assigneeUserId") Long assigneeUserId);

    int submitAcceptance(@Param("tenantId") Long tenantId,
                         @Param("id") Long id,
                         @Param("assigneeUserId") Long assigneeUserId);

    /** 验收通过：限定企业、原状态和非本人维修，写入完成时间并返回影响行数。 */
    int approveWorkOrder(@Param("tenantId") Long tenantId,
                         @Param("id") Long id,
                         @Param("actorUserId") Long actorUserId);

    /** 验收退回：限定企业、待验收状态及非本人维修，保留负责人，返回影响行数。 */
    int rejectWorkOrder(@Param("tenantId") Long tenantId,
                        @Param("id") Long id,
                        @Param("actorUserId") Long actorUserId);
    /** 逾期分页复用原筛选条件，asOf由服务端传入，按截止时间升序。 */
    IPage<WorkOrderEntity> selectOverdueWorkOrderPage(Page<WorkOrderEntity> page,
            @Param("tenantId") Long tenantId, @Param("query") WorkOrderPageQueryDto query,
            @Param("keyword") String keyword, @Param("asOf") java.time.LocalDateTime asOf);

}
