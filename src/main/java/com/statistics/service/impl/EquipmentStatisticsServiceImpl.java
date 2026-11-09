package com.statistics.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.statistics.EquipmentRepairCountQueryDto;
import com.equipops.domain.dto.statistics.EquipmentProcessingDurationQueryDto;
import com.equipops.domain.vo.EquipmentRepairCountVO;
import com.equipops.domain.vo.EquipmentProcessingDurationVO;
import com.statistics.mapper.EquipmentStatisticsMapper;
import com.statistics.service.EquipmentStatisticsService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 基于工单创建时间的报修频次统计，不表示同类故障重复次数。 */
@Service
@RequiredArgsConstructor
public class EquipmentStatisticsServiceImpl implements EquipmentStatisticsService {
    /** 验证账号、企业和成员关系。 */
    private final CurrentAccessService currentAccessService;
    /** 聚合统计SQL。 */
    private final EquipmentStatisticsMapper equipmentStatisticsMapper;

    @Override
    public List<EquipmentRepairCountVO> getRepairCounts(EquipmentRepairCountQueryDto query) {
        //1. 取得可信企业ID，有效普通成员即可查询。
        Long tenantId = currentAccessService.requireActiveTenantId();
        //2. Service保留参数保护，避免内部调用绕过Controller校验。
        if (query == null || query.getDays() == null || query.getDays() < 1 || query.getDays() > 365
                || query.getLimit() == null || query.getLimit() < 1 || query.getLimit() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计天数须为1到365，返回条数须为1到100");
        }
        //3. 近N个上海自然日包含今天，使用左闭右开区间，不遗漏带小数秒的工单。
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var startTime = today.minusDays(query.getDays() - 1L).atStartOfDay();
        var endTime = today.plusDays(1).atStartOfDay();
        //4、5. XML完成分组排序；MyBatis在没有工单时返回空列表。
        return equipmentStatisticsMapper.selectRepairCounts(tenantId, startTime, endTime, query.getLimit());
    }

    @Override
    public List<EquipmentProcessingDurationVO> getProcessingDuration(EquipmentProcessingDurationQueryDto query) {
        //1. 当前企业有效成员可查询，企业ID始终取自可信上下文。
        Long tenantId = currentAccessService.requireActiveTenantId();
        //2. 防止内部调用绕过Controller的参数校验。
        if (query == null || query.getDays() == null || query.getDays() < 1 || query.getDays() > 365
                || query.getLimit() == null || query.getLimit() < 1 || query.getLimit() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计天数须为1到365，返回条数须为1到100");
        }
        //3. 包含上海时区今天，使用完成时间的左闭右开区间。
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var startTime = today.minusDays(query.getDays() - 1L).atStartOfDay();
        var endTime = today.plusDays(1).atStartOfDay();
        //4、5. XML排除无效时间并计算平均秒数；没有有效样本时返回空列表。
        return equipmentStatisticsMapper.selectProcessingDuration(tenantId, startTime, endTime, query.getLimit());
    }

}
