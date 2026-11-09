package com.workspace.service;

import com.equipops.domain.vo.WorkspaceSummaryVO;

/** 当前企业工作台查询契约。 */
public interface WorkspaceService {

    /** 返回真实工单统计及库存统计可用状态。 */
    WorkspaceSummaryVO getSummary();
}
