package com.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 确定性切分：按Markdown标题保留章节路径，超长段落按句末边界切开。 */
public final class AiChunker {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final int MAX_CHARS = 800;
    private static final int OVERLAP = 80;

    private AiChunker() {
    }

    public record Chunk(int chunkNo, String section, String text, String hash) {
    }

    /** 执行流程 1按标题分段 2维护标题路径 3逐段按上限切分 4连续编号。 */
    public static List<Chunk> chunk(String content) {
        // 空文档没有可索引内容。
        if (content == null || content.isBlank()) return List.of();
        // 标题位置用于切段。
        List<int[]> ranges = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        Matcher matcher = HEADING.matcher(content);
        while (matcher.find()) {
            ranges.add(new int[]{matcher.start(), matcher.end()});
            headings.add(matcher.group(1).length() + ":" + matcher.group(2).trim());
        }
        // 逐段生成。
        List<Chunk> chunks = new ArrayList<>();
        int sequence = 1;
        int cursor = 0;
        List<String> path = new ArrayList<>();
        for (int index = 0; index <= ranges.size(); index++) {
            int end = index == ranges.size() ? content.length() : ranges.get(index)[0];
            if (cursor < end) {
                // 当前段落正文。
                String body = content.substring(cursor, end).trim();
                if (!body.isBlank()) {
                    for (String part : split(body)) {
                        chunks.add(new Chunk(sequence++, String.join(" > ", path), part, AiRules.hash(part)));
                    }
                }
            }
            if (index < ranges.size()) {
                // 更新标题层级。
                int level = Integer.parseInt(headings.get(index).substring(0, 1));
                while (path.size() >= level) path.remove(path.size() - 1);
                path.add(headings.get(index).substring(2));
                cursor = ranges.get(index)[1];
            }
        }
        return chunks;
    }

    private static List<String> split(String body) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(body.length(), start + MAX_CHARS);
            if (end < body.length()) {
                // 优先在句末/换行处截断。
                int boundary = Math.max(body.lastIndexOf('。', end), Math.max(body.lastIndexOf('！', end),
                        Math.max(body.lastIndexOf('？', end), body.lastIndexOf('\n', end))));
                if (boundary > start + MAX_CHARS / 2) end = Math.min(start + MAX_CHARS, boundary + 1);
            }
            parts.add(body.substring(start, end).trim());
            if (end >= body.length()) break;
            // 保留少量重叠，保证跨块句子仍可检索。
            start = Math.max(start + 1, end - OVERLAP);
        }
        return parts;
    }
}
