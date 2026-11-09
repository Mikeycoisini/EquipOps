package com.equipops.domain.dto.maintenance;

import com.equipops.domain.enums.MaintenancePlanType;
import com.equipops.domain.dto.maintenance.MaintenancePlanCreateDto.StrictCycleDaysDeserializer;
import com.equipops.domain.dto.maintenance.MaintenancePlanCreateDto.StrictIdDeserializer;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import tools.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 完整修改计划的可编辑字段；不接收创建人、企业及启用状态。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaintenancePlanUpdateDto {
    /** 计划名称。 */
    @NotBlank(message = "计划名称不能为空")
    @Size(max = 160, message = "计划名称最多160个字符")
    private String name;

    /** 关联设备ID。 */
    @NotNull(message = "设备ID不能为空")
    @Positive(message = "设备ID必须大于0")
    @JsonDeserialize(using = StrictIdDeserializer.class)
    private Long equipmentId;

    /** MAINTENANCE表示维护，INSPECTION表示巡检。 */
    @NotNull(message = "计划类型不能为空")
    private MaintenancePlanType planType;

    /** 维护/巡检的具体执行内容。 */
    @NotBlank(message = "执行内容不能为空")
    @Size(max = 2000, message = "执行内容最多2000个字符")
    private String content;

    /** 每隔多少天执行一次。 */
    @NotNull(message = "周期天数不能为空")
    @Min(value = 1, message = "周期至少1天")
    @Max(value = 3650, message = "周期最多3650天")
    @JsonDeserialize(using = StrictCycleDaysDeserializer.class)
    private Integer cycleDays;

    /** 下次到期日；改为新日期时不得早于上海日期的今天，原逾期日期可保持不变。 */
    @NotNull(message = "下次到期日期不能为空")
    private LocalDate nextDueDate;

    /** 当前企业内的负责人用户ID，不是成员关系ID。 */
    @NotNull(message = "负责人用户ID不能为空")
    @Positive(message = "负责人用户ID必须大于0")
    @JsonDeserialize(using = StrictIdDeserializer.class)
    private Long assigneeUserId;

}
