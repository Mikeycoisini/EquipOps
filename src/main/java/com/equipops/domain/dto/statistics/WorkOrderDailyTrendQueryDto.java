package com.equipops.domain.dto.statistics;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 每日工单趋势查询条件，企业ID来自可信登录上下文。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderDailyTrendQueryDto {
    /** 统计近N个上海自然日，包含今天，默认30天，范围1到365天。 */
    @NotNull(message = "统计天数不能为空")
    @Min(value = 1, message = "统计天数不能小于1")
    @Max(value = 365, message = "统计天数不能超过365")
    private Integer days = 30;
}
