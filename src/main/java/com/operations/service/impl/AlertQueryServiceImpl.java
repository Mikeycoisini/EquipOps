package com.operations.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.operations.AlertPageQueryDto;
import com.equipops.domain.vo.AlertEventVO;
import com.operations.mapper.AlertEventMapper;
import com.operations.service.AlertQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;


@Service
@RequiredArgsConstructor
public class AlertQueryServiceImpl implements AlertQueryService {
    // 身份校验服务
    private final CurrentAccessService currentAccessService;
    // 告警数据访问接口
    private final AlertEventMapper alertEventMapper;

    /**
     * 分页查询
     * @param query
     * @return
     */
    @Override
    public IPage<AlertEventVO> page(AlertPageQueryDto query) {
        // 校验当前企业管理员身份，取得服务端确认的企业 ID；校验失败直接抛出异常。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        // 检查查询对象是否为空、页码是否小于 1、每页条数是否超出 1 至 100。
        if (query == null || query.getCurrent() < 1 || query.getSize() < 1 || query.getSize() > 100
                || (query.getRuleId() != null && query.getRuleId() <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "告警分页参数有误");
        }
        // 使用已校验的页码和条数创建分页对象
        Page<AlertEventVO> page = new Page<>(query.getCurrent(), query.getSize());
        // 将分页对象、可信企业 ID 和筛选条件传给 Mapper，并用 result 接住查询结果。
        IPage<AlertEventVO> result = alertEventMapper.selectEventPage(page, tenantId, query);
        if (result == null) throw new IllegalStateException("告警分页结果未返回");
        return result;
    }

    // 实现详情查询方法，接收告警 ID 并返回一条公开 VO。
    @Override
    public AlertEventVO detail(Long id) {
        // 校验当前企业管理员身份，取得服务端确认的企业 ID；校验失败直接抛出异常。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        // 告警 ID 为空或不为正数时返回 HTTP 400，停止后续查询。
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "告警ID无效");
        }
        // 根据可信企业 ID 和告警 ID 查询详情，将结果接到 event 变量。
        AlertEventVO event = alertEventMapper.selectEventDetail(tenantId, id);
        // 未查到当前企业内的告警时返回 HTTP 404，其他企业的告警同样不可见。
        if (event == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "告警不存在");
        return event;
    }
}
