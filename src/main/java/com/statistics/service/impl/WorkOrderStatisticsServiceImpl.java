package com.statistics.service.impl;

import com.equipops.domain.dto.statistics.WorkOrderDailyTrendQueryDto;
import com.equipops.domain.vo.WorkOrderDailyTrendVO;
import com.equipops.domain.vo.WorkOrderOverdueSummaryVO;
import com.statistics.service.WorkOrderStatisticsService;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import com.auth.service.CurrentAccessService;
import com.statistics.mapper.WorkOrderStatisticsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 每日工单趋势，统一使用上海自然日，并补齐缺失日期。 */
@Service
@RequiredArgsConstructor
public class WorkOrderStatisticsServiceImpl implements WorkOrderStatisticsService {
    /** 当前账号、企业与成员关系校验。 */
    private final CurrentAccessService currentAccessService;
    /** 按创建日、完成日独立汇总。 */
    private final WorkOrderStatisticsMapper workOrderStatisticsMapper;
    @Override
    public List<WorkOrderDailyTrendVO> getDailyTrend(WorkOrderDailyTrendQueryDto query) {
        //1. 普通有效企业成员可查，企业ID只从可信上下文取得。w
        Long tenantId = currentAccessService.requireActiveTenantId();
        //2. 保留Service参数校验，防止内部调用绕过Controller校验。
        if (query == null || query.getDays() == null || query.getDays() < 1 || query.getDays() > 365) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计天数须为1到365");
        }
        int days = query.getDays();
        //3. 近N个自然日包含今天，查询区间为开始日零点至明天零点。
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate startDate = today.minusDays(days - 1L);
        //4. XML分别按创建时间和完成时间聚合，不以创建日期限制完成数。
        List<WorkOrderDailyTrendVO> rows = workOrderStatisticsMapper.selectDailyTrend(
                tenantId, startDate.atStartOfDay(), today.plusDays(1).atStartOfDay());
        Map<LocalDate, WorkOrderDailyTrendVO> byDate = new HashMap<>();
        for (WorkOrderDailyTrendVO row : rows) {
            byDate.put(row.getDate(), row);
        }
        //5. 按连续日期组装结果；完全没有工单的日期也返回两个0。
        List<WorkOrderDailyTrendVO> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            result.add(byDate.getOrDefault(date, new WorkOrderDailyTrendVO(date, 0L, 0L)));
        }
        return result;
    }
    @Override
    public WorkOrderOverdueSummaryVO getOverdueSummary() {
        //1. 校验当前账号、企业及成员资格，普通有效成员即可查询。
        Long tenantId = currentAccessService.requireActiveTenantId();
        //2. 本次请求只取一次上海时间，保证全部计数使用同一逾期基准。
        LocalDateTime asOf = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        //3. XML限定企业和未完成状态；没有记录时数量为0，并返回统计基准时间。
        return workOrderStatisticsMapper.selectOverdueSummary(tenantId, asOf);
    }

}
