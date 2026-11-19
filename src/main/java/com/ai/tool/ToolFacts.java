package com.ai.tool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** 工具事实统一格式化。 */
public final class ToolFacts {
    private ToolFacts() {
    }

    public static void put(Map<String, Object> fact, String key, Object value) {
        if (value != null) fact.put(key, value);
    }

    public static String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    public static String time(LocalDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public static String date(LocalDate value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static String quantity(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    public static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return value.substring(0, maximum);
    }
}
