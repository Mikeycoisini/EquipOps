package com.equipops.domain.dto.equipment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** 建立设备与备件适配关系；设备ID在路径中，企业与创建人由服务端确定。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EquipmentSparePartCreateDto {
    /** 当前企业内需要关联的备件ID。 */
    @NotNull(message = "备件ID不能为空")
    @Positive(message = "备件ID必须大于0")
    @JsonDeserialize(using = StrictIdDeserializer.class)
    private Long sparePartId;

    /** 只接受JSON整数，避免将30.5静默截断后关联到另一条备件。 */
    public static class StrictIdDeserializer extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Long.class, "备件ID必须是JSON整数");
            }
            return parser.getLongValue();
        }
    }
}
