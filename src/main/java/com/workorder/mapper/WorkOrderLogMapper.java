package com.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.equipops.domain.entity.WorkOrderLogEntity;
import com.equipops.domain.vo.WorkOrderLogVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 工单日志数据访问接口。 */
@Mapper
public interface WorkOrderLogMapper extends BaseMapper<WorkOrderLogEntity> {
    /** 按当前企业和工单查询日志，按发生时间、ID 升序排列。 */
    List<WorkOrderLogEntity> selectByWorkOrderId(@Param("tenantId") Long tenantId,
                                               @Param("workOrderId") Long workOrderId);

    /** 查询当前企业工单日志及操作人显示名，保留停用成员与无操作人资料的历史日志。 */
    List<WorkOrderLogVO> selectViewByWorkOrderId(@Param("tenantId") Long tenantId,
                                              @Param("workOrderId") Long workOrderId);
}
