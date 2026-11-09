package com.equipops.domain.dto.maintenance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 执行结果说明；企业、执行人和执行时间由服务端确定。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceExecutionCreateDto {
    /** 本次维护或巡检的执行结果，不能为空或纯空白。 */
    @NotBlank(message = "执行结果不能为空")
    @Size(max = 2000, message = "执行结果最多2000个字符")
    private String result;
}
