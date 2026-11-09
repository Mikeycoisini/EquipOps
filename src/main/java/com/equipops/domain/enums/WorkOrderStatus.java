package com.equipops.domain.enums;

public enum WorkOrderStatus { PENDING, IN_PROGRESS, AWAITING_ACCEPTANCE, COMPLETED }
/**
 * 报修新建：状态为 PENDING（待处理）。
 *
 * 派单：状态不变（依然是 PENDING），只是把负责人 assigneeUserId 填上。
 *
 * 开始维修：PENDING ➡️ IN_PROGRESS。
 *
 * 提交验收：IN_PROGRESS ➡️ AWAITING_ACCEPTANCE。

 * 验收通过：AWAITING_ACCEPTANCE ➡️ COMPLETED。
 *
 * 验收退回：AWAITING_ACCEPTANCE ➡️ IN_PROGRESS（记录退回原因）
 */
