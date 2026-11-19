package com.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 标题路径、长度上限与连续编号。 */
class AiChunkerTest {
    @Test
    void keepsSectionPath() {
        List<AiChunker.Chunk> chunks = AiChunker.chunk("# 一级\n内容一\n## 二级\n轴承更换步骤");
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.section().equals("一级 > 二级")));
    }

    @Test
    void splitsLongTextAndNumbersFromOne() {
        String text = "轴承更换步骤。".repeat(300);
        List<AiChunker.Chunk> chunks = AiChunker.chunk(text);
        assertTrue(chunks.size() > 1);
        for (int index = 0; index < chunks.size(); index++) {
            assertEquals(index + 1, chunks.get(index).chunkNo());
            assertTrue(chunks.get(index).text().length() <= 800);
        }
    }

    @Test
    void emptyDocumentReturnsEmptyList() {
        assertTrue(AiChunker.chunk("   ").isEmpty());
    }
}
