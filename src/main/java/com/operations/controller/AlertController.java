package com.operations.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.common.response.Result;
import com.equipops.domain.dto.operations.AlertPageQueryDto;
import com.equipops.domain.vo.AlertEventVO;
import com.operations.service.AlertQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 当前企业管理员告警列表及详情；权限与归属检查在Service内执行。
 */
@RestController
@RequestMapping("/api/operations/alerts")
@RequiredArgsConstructor
public class AlertController {
    private final AlertQueryService alertQueryService;

    @GetMapping
    public Result<IPage<AlertEventVO>> page(
            @Valid @ModelAttribute AlertPageQueryDto query) {
        return Result.success(alertQueryService.page(query));
    }

    @GetMapping("/{id}")
    public Result<AlertEventVO> detail(
            @PathVariable("id") Long id) {
        return Result.success(alertQueryService.detail(id));
    }
}
