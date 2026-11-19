package com.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** 引用核对、拒答与事实-only回答规则。 */
class AiRulesTest {
    private static AiRules.Citation citation(String chunkId) {
        return new AiRules.Citation(chunkId, "2", "3", "章节", "权威原文", "DOCUMENT", null);
    }

    @Test
    void deduplicatesCitationsAndKeepsOrder() {
        var answer = new AiRules.PythonAnswer("SUCCEEDED", "回答",
                List.of(new AiRules.Reference("7"), new AiRules.Reference("7"), new AiRules.Reference("6")), List.of());
        var result = AiRules.resolve(answer, List.of(citation("7"), citation("6")));
        assertEquals(List.of("7", "6"), result.citations().stream().map(AiRules.Citation::chunkId).toList());
    }

    @Test
    void fabricatedCitationRefusesWholeAnswer() {
        var answer = new AiRules.PythonAnswer("SUCCEEDED", "回答", List.of(new AiRules.Reference("999")), List.of());
        var result = AiRules.resolve(answer, List.of(citation("7")));
        assertEquals("INSUFFICIENT_EVIDENCE", result.state());
        assertEquals("", result.answer());
        assertEquals(List.of("INVALID_CITATIONS"), result.warnings());
    }

    @Test
    void verifiedFactsCanAnswerWithoutCitations() {
        var answer = new AiRules.PythonAnswer("SUCCEEDED", "库存为0", List.of(),
                List.of(Map.of("toolName", "get_inventory_stock", "asOf", "2026-11-19T00:00:00Z")), List.of());
        var result = AiRules.resolve(answer, List.of());
        assertEquals("SUCCEEDED", result.state());
        assertTrue(result.citations().isEmpty());
        assertEquals(1, result.businessFacts().size());
        assertTrue(result.warnings().contains("NO_DOCUMENT_EVIDENCE"));
    }

    @Test
    void blankAnswerOrNoFactsStillRefuses() {
        var blank = new AiRules.PythonAnswer("SUCCEEDED", "   ", List.of(new AiRules.Reference("7")), List.of());
        assertEquals("INSUFFICIENT_EVIDENCE", AiRules.resolve(blank, List.of(citation("7"))).state());
        var empty = new AiRules.PythonAnswer("SUCCEEDED", "回答", List.of(), List.of());
        assertEquals("INSUFFICIENT_EVIDENCE", AiRules.resolve(empty, List.of()).state());
    }

    @Test
    void warningCodesAreFiltered() {
        var answer = new AiRules.PythonAnswer("INSUFFICIENT_EVIDENCE", "不应交付", List.of(),
                List.of("SOURCE_DELETED", "java.sql.SQLException: denied", "lowercase"));
        var result = AiRules.resolve(answer, List.of());
        assertEquals(List.of("SOURCE_DELETED"), result.warnings());
        assertEquals("", result.answer());
    }

    @Test
    void moreThanTwentyCitationsIsProtocolError() {
        List<AiRules.Reference> references = new ArrayList<>();
        for (int index = 1; index <= 21; index++) references.add(new AiRules.Reference("c" + index));
        var failure = assertThrows(ResponseStatusException.class, () -> AiRules.resolve(
                new AiRules.PythonAnswer("SUCCEEDED", "回答", references, List.of()), List.of()));
        assertEquals(502, failure.getStatusCode().value());
    }
}
