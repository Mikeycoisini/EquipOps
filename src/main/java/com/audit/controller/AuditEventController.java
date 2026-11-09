package com.audit.controller;

import com.audit.service.AuditEventService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.common.response.Result;
import com.equipops.domain.dto.audit.AuditEventPageQueryDto;
import com.equipops.domain.vo.AuditEventDetailVO;
import com.equipops.domain.vo.AuditEventVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 企业管理员审计查询入口；不提供编辑和删除。 */
@RestController
@RequestMapping("/api/audit-events")
@RequiredArgsConstructor
public class AuditEventController {

    private final AuditEventService auditEventService;

    /** 分页查询当前企业审计事件。 */
    @GetMapping
    public Result<IPage<AuditEventVO>> page(@Valid @ModelAttribute AuditEventPageQueryDto query) {
        // URL 查询参数绑定到 query；企业范围及管理员校验由 Service 负责。
        return Result.success(auditEventService.page(query));
    }

    /** 查询当前企业审计详情；其他企业的事件按不存在处理。 */
    @GetMapping("/{id}")
    public Result<AuditEventDetailVO> detail(@PathVariable("id") Long id) {
        // 路径只提供事件 ID，企业范围由 Service 从登录状态取得。
        return Result.success(auditEventService.detail(id));
    }
}
