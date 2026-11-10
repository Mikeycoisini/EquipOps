package com.team.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 团队请求局部整数解码，禁止JSON小数静默截断成版本或对象ID。 */
public final class TeamInput {
    private TeamInput() {}
    public static final long MAX_VERSION=9_007_199_254_740_991L;
    public static class Version extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
            // 版本只接受JSON整数，非负与安全范围由DTO和服务共同校验。
            if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT) return context.reportInputMismatch(Long.class,"版本必须是JSON整数");
            return parser.getLongValue();
        }
    }
    public static class Id extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
            // 对象ID允许完整整数或规范十进制字符串，以兼容浏览器BIGINT。
            if(parser.currentToken()==JsonToken.VALUE_NUMBER_INT) return parser.getLongValue();
            if(parser.currentToken()==JsonToken.VALUE_STRING) {
                String text=parser.getString();
                if(text.matches("[1-9][0-9]{0,18}")) {
                    try {return Long.parseLong(text);} catch(NumberFormatException overflow) {
                        return context.reportInputMismatch(Long.class,"ID超出整数范围");
                    }
                }
            }
            return context.reportInputMismatch(Long.class,"ID必须为整数或十进制正整数字符串");
        }
    }
}
