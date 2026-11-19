package com.ai.tool;

import java.time.Duration;
import java.util.regex.Pattern;

/** 只读工具固定边界，不能被模型参数扩大。 */
public final class ToolLimits {
    public static final int MAX_ROWS = 20;
    public static final int MAX_CALLS_PER_TASK = 4;
    public static final Duration TIMEOUT = Duration.ofSeconds(3);
    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    public static final int MAX_ARGUMENT_BYTES = 8192;
    public static final int MAX_WARNINGS = 20;

    private ToolLimits() {
    }
}
