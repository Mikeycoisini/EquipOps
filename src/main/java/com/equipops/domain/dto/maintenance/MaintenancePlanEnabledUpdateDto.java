package com.equipops.domain.dto.maintenance;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 仅修改维护计划启用状态，不接收企业、周期及设备等字段。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaintenancePlanEnabledUpdateDto {
    /** true启用、false停用；不能省略，避免误把缺省值当作停用。 */
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}
