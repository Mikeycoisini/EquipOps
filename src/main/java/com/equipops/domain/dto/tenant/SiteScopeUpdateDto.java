package com.equipops.domain.dto.tenant;

import jakarta.validation.constraints.*;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** 完整替换成员显式厂区范围，空集合表示撤销全部普通范围。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SiteScopeUpdateDto {
    /** 乐观锁版本只接受JSON整数，范围限定为浏览器安全整数。 */
    @NotNull @PositiveOrZero @Max(9_007_199_254_740_991L)
    @JsonDeserialize(using = StrictVersionDeserializer.class)
    private Long expectedVersion;
    /** 厂区ID接受整数或十进制整数字符串，拒绝小数截断。 */
    @NotNull @Size(max = 500)
    @JsonDeserialize(contentUsing = StrictSiteIdDeserializer.class)
    private List<@NotNull @Positive Long> siteIds;

    /** 只作用于范围请求版本，不更改全局Jackson转换规则。 */
    public static class StrictVersionDeserializer extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            // 1. JSON浮点数、字符串和布尔值均不能静默转换为版本。
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Long.class, "范围版本必须是JSON整数");
            }
            // 2. 数据类型溢出交给解析器拒绝，非负和安全范围由DTO校验。
            return parser.getLongValue();
        }
    }

    /** 兼容字符串BIGINT厂区ID，同时拒绝浮点数、指数形式及溢出输入。 */
    public static class StrictSiteIdDeserializer extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            // 1. 完整JSON整数直接读取，正数约束由DTO统一校验。
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) return parser.getLongValue();
            // 2. 只有规范十进制正整数字符串才能转换，不能接受小数或任意正文。
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                String text = parser.getString();
                if (text.matches("[1-9][0-9]{0,18}")) {
                    try {
                        return Long.parseLong(text);
                    } catch (NumberFormatException overflow) {
                        // 3. 超出Long的整数字符串同样拒绝，不截断或回退为0。
                        return context.reportInputMismatch(Long.class, "厂区ID超出整数范围");
                    }
                }
            }
            // 4. 非法类型统一成为400，不将格式错误的ID转换成其他有效厂区。
            return context.reportInputMismatch(Long.class, "厂区ID必须是整数或十进制整数字符串");
        }
    }
}
