package com.statistics.mapper;

import com.equipops.domain.vo.WorkOrderDailyTrendVO;
import com.equipops.domain.vo.WorkOrderOverdueSummaryVO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 工单按日聚合，已包含在com.statistics.mapper扫描范围内。 */
public interface WorkOrderStatisticsMapper {
    /** 查询[startTime,endTime)内的新增和完成数；无记录日期由Service补齐。 */
    List<WorkOrderDailyTrendVO> selectDailyTrend(@Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
    /** 仅汇总未完成状态，无记录时数量返回0；asOf必须由服务端确定。 */
    WorkOrderOverdueSummaryVO selectOverdueSummary(@Param("tenantId") Long tenantId,
            @Param("asOf") LocalDateTime asOf);

}
