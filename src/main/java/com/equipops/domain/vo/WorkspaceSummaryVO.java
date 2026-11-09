package com.equipops.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 当前企业工作台汇总；库存指标未接入时明确返回不可用，不能用零代替未知。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceSummaryVO {

    /** 当前企业 ID，由服务端验证后的企业上下文确定。 */
    private Long tenantId;

    /** 当前企业工单总数。 */
    private Long totalOrderCount;

    /** 待派单数量：状态为 PENDING 且负责人为空。 */
    private Long pendingDispatchCount;

    /** 待开始维修数量：状态为 PENDING 且已有负责人。 */
    private Long pendingStartCount;

    /** 维修中数量：状态为 IN_PROGRESS。 */
    private Long inProgressCount;

    /** 待验收数量：状态为 AWAITING_ACCEPTANCE。 */
    private Long awaitingAcceptanceCount;

    /** 已完成数量：状态为 COMPLETED。 */
    private Long completedCount;

    /** 低库存数量；尚未接入仓库库存时为 null，不代表库存风险数量为零。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Long lowStockCount;

    /** 是否能提供真实仓库库存的低库存统计。 */
    private Boolean lowStockAvailable;

    /** 低库存统计不可用时的说明，供前端展示。 */
    private String lowStockUnavailableReason;
}
