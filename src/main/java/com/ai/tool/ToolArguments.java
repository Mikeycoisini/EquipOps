package com.ai.tool;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 最小JSON Schema校验：拒绝未知键、字符串数字与越界值。 */
public final class ToolArguments {
    private ToolArguments() {
    }

    /** 执行流程 1确认对象 2拒绝未知键 3核对必填 4逐字段校验。 */
    public static Map<String, Object> validate(Map<String, Object> schema, JsonNode arguments, ObjectMapper json) {
        if (schema == null || arguments == null || !arguments.isObject()) throw invalid();
        @SuppressWarnings("unchecked")
        Map<String, Object> values = json.convertValue(arguments, Map.class);
        Map<String, Object> properties = object(schema.get("properties"));
        for (Object name : values.keySet()) {
            if (!(name instanceof String key) || !properties.containsKey(key)) throw invalid();
        }
        for (Object required : list(schema.get("required"))) {
            if (!(required instanceof String key) || !values.containsKey(key)) throw invalid();
        }
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            if (!values.containsKey(property.getKey())) continue;
            validateValue(object(property.getValue()), values.get(property.getKey()));
        }
        return Map.copyOf(values);
    }

    private static void validateValue(Map<String, Object> definition, Object value) {
        String type = String.valueOf(definition.get("type"));
        if ("integer".equals(type)) {
            long number;
            if (value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
                try {
                    number = value instanceof BigInteger big ? big.longValueExact() : ((Number) value).longValue();
                } catch (ArithmeticException overflow) {
                    throw invalid();
                }
            } else {
                throw invalid();
            }
            if (definition.containsKey("minimum") && number < number(definition.get("minimum"))) throw invalid();
            if (definition.containsKey("maximum") && number > number(definition.get("maximum"))) throw invalid();
            return;
        }
        if ("string".equals(type)) {
            if (!(value instanceof String text)) throw invalid();
            if (definition.containsKey("minLength") && text.length() < number(definition.get("minLength"))) throw invalid();
            if (definition.containsKey("maxLength") && text.length() > number(definition.get("maxLength"))) throw invalid();
            if (definition.containsKey("pattern") && !text.matches(String.valueOf(definition.get("pattern")))) throw invalid();
            return;
        }
        throw new IllegalArgumentException("AI_TOOL_SCHEMA_INVALID");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("AI_TOOL_SCHEMA_INVALID");
        return (Map<String, Object>) map;
    }

    private static List<?> list(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> items)) throw new IllegalArgumentException("AI_TOOL_SCHEMA_INVALID");
        return items;
    }

    private static long number(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("AI_TOOL_SCHEMA_INVALID");
        return number.longValue();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_TOOL_INVALID_ARGUMENTS");
    }
}
