package com.equipops.domain.dto.maintenance;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 执行历史分页参数；计划ID来自路径，企业来自登录上下文。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceExecutionPageQueryDto {
    /** 当前页，默认1；小于1时按1处理。 */
    private long current = 1;
    /** 每页条数，默认10；业务层限制为1到100。 */
    private long size = 10;
}
