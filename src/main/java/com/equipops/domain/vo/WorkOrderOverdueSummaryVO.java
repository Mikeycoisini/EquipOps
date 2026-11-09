package com.equipops.domain.vo;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 当前企业未完成工单的逾期汇总，不限制工单创建日期。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderOverdueSummaryVO {
    /** 本次统计基准时间，由服务端按上海时区确定。 */
    private LocalDateTime asOf;

    /** 逾期总数，为下面三个逾期状态数量之和；截止时间严格早于asOf。 */
    private Long overdueCount;

    /** PENDING状态的逾期数量，包含待派单和已派单未开工。 */
    private Long pendingOverdueCount;

    /** IN_PROGRESS状态的逾期数量。 */
    private Long inProgressOverdueCount;

    /** AWAITING_ACCEPTANCE状态的逾期数量。 */
    private Long awaitingAcceptanceOverdueCount;

    /** 未完成且没有设置截止时间的数量，不能据此判定是否逾期。 */
    private Long withoutDeadlineCount;
}
