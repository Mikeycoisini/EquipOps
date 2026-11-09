package com.equipops.domain.dto.statistics;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 设备报修频次查询条件，企业范围取自可信登录上下文。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EquipmentRepairCountQueryDto {
    /** 近多少个自然日，包含上海时区今天，默认30天，范围1到365天。 */
    @NotNull(message = "统计天数不能为空")
    @Min(value = 1, message = "统计天数不能小于1")
    @Max(value = 365, message = "统计天数不能超过365")
    private Integer days = 30;

    /** 最多返回多少台设备，默认10台，范围1到100台。 */
    @NotNull(message = "返回条数不能为空")
    @Min(value = 1, message = "返回条数不能小于1")
    @Max(value = 100, message = "返回条数不能超过100")
    private Integer limit = 10;
}
