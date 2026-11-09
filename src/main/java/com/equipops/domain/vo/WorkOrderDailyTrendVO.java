package com.equipops.domain.vo;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 每日工单趋势项；最终结果按日期升序，并由业务层为缺失日期补零。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderDailyTrendVO {
    /** 上海时区的统计日期。 */
    private LocalDate date;

    /** 当天创建的工单数，按created_at统计，不限制当前工单状态。 */
    private Long createdCount;

    /** 当天完成的工单数，按completed_at统计，不受工单创建日期限制。 */
    private Long completedCount;
}
