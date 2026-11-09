package com.equipops.domain.enums;

/** 库存流水Excel导出任务状态；具体流转规则在后续业务层实现。 */
public enum InventoryExportStatus {
    /** 已创建并等待执行。 */
    QUEUED,
    /** 后台正在查询数据并生成Excel。 */
    RUNNING,
    /** 管理员已请求取消，等待后台停止并清理文件。 */
    CANCEL_REQUESTED,
    /** 文件已生成并成功发布，可在有效期内授权下载。 */
    SUCCEEDED,
    /** 查询、权限、IO、超时或执行实例失联等原因导致失败。 */
    FAILED,
    /** 已取消，结果文件不可下载。 */
    CANCELLED,
    /** 执行器容量不足，任务被明确拒绝。 */
    REJECTED,
    /** 成功文件已超过下载有效期。 */
    EXPIRED
}
