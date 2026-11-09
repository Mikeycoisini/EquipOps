package com.equipops.domain.dto.maintenance;

import com.equipops.domain.enums.MaintenancePlanType;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 创建日期周期计划；企业、厂区、创建人及启用状态由后端确定。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaintenancePlanCreateDto {
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

    /** 首次到期日，JSON格式YYYY-MM-DD；是否已过期由业务层按上海时区校验。 */
    @NotNull(message = "首次到期日期不能为空")
    private LocalDate nextDueDate;

    /** 当前企业内的负责人用户ID，不是成员关系ID。 */
    @NotNull(message = "负责人用户ID不能为空")
    @Positive(message = "负责人用户ID必须大于0")
    @JsonDeserialize(using = StrictIdDeserializer.class)
    private Long assigneeUserId;

    /** 只对本请求生效，避免默认解析将7.9天静默截断为7天。 */
    public static class StrictCycleDaysDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "周期天数必须是JSON整数");
            }
            return parser.getIntValue();
        }
    }

    /** ID必须是完整整数，不能将另一条小数形式的输入截断为有效ID。 */
    public static class StrictIdDeserializer extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Long.class, "关联ID必须是JSON整数");
            }
            return parser.getLongValue();
        }
    }
}
