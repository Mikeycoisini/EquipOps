package com.workspace.controller;

import com.common.response.Result;
import com.equipops.domain.vo.WorkspaceSummaryVO;
import com.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工作台查询入口，企业范围由登录上下文确定。 */
@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    /** 工作台汇总业务服务。 */
    private final WorkspaceService workspaceService;

    /** 汇总当前企业的工单待办，并明确库存统计是否可用。 */
    @GetMapping("/summary")
    public Result<WorkspaceSummaryVO> summary() {
        // 不接收前端指定的 tenantId；Service 负责身份校验和数据查询。
        return Result.success(workspaceService.getSummary());
    }
}
