package com.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 回答与引用的纯规则：只信数据库候选，不采信Python正文和原文。 */
public final class AiRules {
    private AiRules() {
    }

    /** Python只允许回传chunkId。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reference(String chunkId) {
    }

    /** 数据库回取的权威引用。 */
    public record Citation(String chunkId, String versionId, String documentId,
                           String section, String quote, String sourceType, String sourceOrderId) {
    }

    /** Python回答四分区；businessFacts在Java侧校验后才可信。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PythonAnswer(String state, String answer, List<Reference> citations,
                               List<Object> businessFacts, List<String> warnings) {
        public PythonAnswer(String state, String answer, List<Reference> citations, List<String> warnings) {
            this(state, answer, citations, List.of(), warnings);
        }
    }

    /** 对外统一回答。 */
    public record Answer(String state, String answer, List<Citation> citations,
                         List<Object> businessFacts, List<String> warnings) {
    }

    /**
     * 执行流程 1校验上游状态与上限 2过滤警告 3建立权威候选 4核对引用
     * 5处理零引用事实回答 6组装四分区。
     */
    public static Answer resolve(PythonAnswer generated, List<Citation> authoritative) {
        // 上游状态只能是成功或拒答。
        if (generated == null || generated.state() == null
                || !List.of("SUCCEEDED", "INSUFFICIENT_EVIDENCE").contains(generated.state())
                || generated.answer() == null || generated.answer().length() > 8000
                || generated.citations() == null || generated.citations().size() > 20) {
            throw upstream();
        }
        // 警告只保留短安全码。
        List<String> warnings = generated.warnings() == null ? List.of() : generated.warnings().stream()
                .filter(value -> value != null && value.matches("[A-Z0-9_]{1,80}"))
                .limit(20).toList();
        // 拒答状态不交付正文与事实。
        if ("INSUFFICIENT_EVIDENCE".equals(generated.state())) return refusal(warnings);
        // 权威候选只来自当前企业、当前任务所选版本。
        var allowed = new LinkedHashMap<String, Citation>();
        for (Citation citation : authoritative) allowed.put(citation.chunkId(), citation);
        // 引用按模型顺序去重；任一伪引用整答拒答。
        var selected = new LinkedHashMap<String, Citation>();
        for (Reference reference : generated.citations()) {
            if (reference == null || !allowed.containsKey(reference.chunkId())) return refusal(List.of("INVALID_CITATIONS"));
            selected.put(reference.chunkId(), allowed.get(reference.chunkId()));
        }
        // 事实最多20条，进入本方法前必须已由注册表核对。
        List<Object> facts = generated.businessFacts() == null ? List.of()
                : generated.businessFacts().stream().limit(20).toList();
        // 空白正文不能交付。
        if (generated.answer().isBlank()) return refusal(List.of("INVALID_CITATIONS"));
        // 零引用但存在已验证系统事实时允许回答，并显式标注无资料依据。
        if (selected.isEmpty()) {
            if (facts.isEmpty()) return refusal(List.of("INVALID_CITATIONS"));
            List<String> factOnly = new ArrayList<>(warnings);
            if (!factOnly.contains("NO_DOCUMENT_EVIDENCE")) factOnly.add("NO_DOCUMENT_EVIDENCE");
            return new Answer("SUCCEEDED", generated.answer(), List.of(), facts, factOnly);
        }
        // 正常四分区交付。
        return new Answer("SUCCEEDED", generated.answer(), new ArrayList<>(selected.values()), facts, warnings);
    }

    public static Answer refusal(List<String> warnings) {
        return new Answer("INSUFFICIENT_EVIDENCE", "", List.of(), List.of(), List.copyOf(warnings));
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("AI_HASH_UNAVAILABLE");
        }
    }

    private static ResponseStatusException upstream() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE");
    }
}
