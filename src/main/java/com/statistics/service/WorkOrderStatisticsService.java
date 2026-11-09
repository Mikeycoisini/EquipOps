package com.statistics.service;

import com.equipops.domain.dto.statistics.WorkOrderDailyTrendQueryDto;
import com.equipops.domain.vo.WorkOrderDailyTrendVO;
import com.equipops.domain.vo.WorkOrderOverdueSummaryVO;
import java.util.List;

/** 每日工单趋势查询业务。 */
public interface WorkOrderStatisticsService {
    /** 最终返回连续日期，缺失日期补零，按日期升序排列。 */
    List<WorkOrderDailyTrendVO> getDailyTrend(WorkOrderDailyTrendQueryDto query);
    /** 汇总当前企业未完成工单，以服务端统计时间判断是否逾期。 */
    WorkOrderOverdueSummaryVO getOverdueSummary();

}
