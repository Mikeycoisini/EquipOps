package com.equipops.domain.dto.operations;
import jakarta.validation.constraints.*;
import lombok.Data;
/** 创建时expectedVersion为空，修改时必须提供当前版本。 */
@Data
public class AlertRuleSaveDto {
    /** 开关由管理员显式选择。 */
    @NotNull
    private Boolean enabled;
    /** 触发阈值，限制单窗口证据数量预算。 */
    @NotNull @Min(1) @Max(10000)
    private Integer thresholdCount;
    /** 统计窗口秒数，最大一天。 */
    @NotNull @Min(1) @Max(86400)
    private Integer windowSeconds;
    /** 冷却秒数，最大一天。 */
    @NotNull @Min(0) @Max(86400)
    private Integer cooldownSeconds;
    /** 本企业有效管理员负责人。 */
    @NotNull @Positive
    private Long ownerUserId;
    /** 条件更新版本。 */
    @Min(0)
    private Long expectedVersion;
}
