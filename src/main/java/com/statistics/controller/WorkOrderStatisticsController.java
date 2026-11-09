package com.statistics.controller;

import com.common.response.Result;
import com.equipops.domain.dto.statistics.WorkOrderDailyTrendQueryDto;
import com.equipops.domain.vo.WorkOrderDailyTrendVO;
import com.equipops.domain.vo.WorkOrderOverdueSummaryVO;
import com.statistics.service.WorkOrderStatisticsService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工单统计入口，企业范围由业务层从可信上下文取得。 */
@RestController
@RequestMapping("/api/statistics/work-orders")
@RequiredArgsConstructor
public class WorkOrderStatisticsController {
    private final WorkOrderStatisticsService workOrderStatisticsService;

    /** 查询每日新增和完成数量，按日期升序返回并补齐零值日期。 */
    @GetMapping("/daily-trend")
    public Result<List<WorkOrderDailyTrendVO>> dailyTrend(@Valid @ModelAttribute WorkOrderDailyTrendQueryDto query) {
        return Result.success(workOrderStatisticsService.getDailyTrend(query));
    }
    /** 当前企业未完成工单的逾期汇总；不接收客户端企业ID或统计时间。 */
    @GetMapping("/overdue-summary")
    public Result<WorkOrderOverdueSummaryVO> overdueSummary() {
        return Result.success(workOrderStatisticsService.getOverdueSummary());
    }

}
