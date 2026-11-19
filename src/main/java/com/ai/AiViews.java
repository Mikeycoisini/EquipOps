package com.ai;

import java.util.List;

/** 对外视图只包含浏览器需要的字段，不暴露storage_key、令牌和内部SQL。 */
public final class AiViews {
    private AiViews() {
    }

    /** 资料列表/详情视图。 */
    public record Document(Long id, String documentCode, String title, String state,
                           Long latestVersionId, Integer versionNo, String indexState,
                           String createdAt, String updatedAt) {
    }

    /** 任务状态与四分区结果。 */
    public record Task(Long id, String state, String question, String answer,
                       List<Citation> citations, List<Object> businessFacts,
                       List<String> warnings, String errorCode,
                       String createdAt, String updatedAt) {
    }

    /** 引用原文由Java从数据库回取，python只提供chunkId。 */
    public record Citation(String chunkId, String documentId, String versionId,
                           String section, String quote) {
    }
}
