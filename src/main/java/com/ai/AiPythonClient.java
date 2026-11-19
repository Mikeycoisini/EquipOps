package com.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Python Agent 单次调用客户端；有界超时、不重试、不反射内部异常。 */
public class AiPythonClient {
    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http;

    public AiPythonClient(String baseUrl, ObjectMapper json) {
        // 地址只由配置提供。
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.json = json;
        // 连接两秒上限。
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /** 执行流程 1组装任务与片段快照 2限时POST 3校验状态码 4解析四分区。 */
    public AiRules.PythonAnswer query(long taskId, long tenantId, String question,
                                      List<AiDocumentStore.ChunkRef> chunks,
                                      String toolToken, List<String> tools, Duration remaining) {
        // 按版本分组，Python据此做词面检索。
        Map<String, List<Map<String, Object>>> documents = new LinkedHashMap<>();
        for (AiDocumentStore.ChunkRef chunk : chunks) {
            documents.computeIfAbsent(Long.toString(chunk.versionId()), key -> new ArrayList<>())
                    .add(Map.of("chunkId", Long.toString(chunk.chunkId()),
                            "section", chunk.section() == null ? "" : chunk.section(),
                            "text", chunk.text()));
        }
        List<Map<String, Object>> payloadDocuments = documents.entrySet().stream()
                .map(entry -> Map.<String, Object>of("versionId", entry.getKey(), "chunks", entry.getValue()))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", Long.toString(taskId));
        body.put("tenantId", Long.toString(tenantId));
        body.put("question", question);
        body.put("documents", payloadDocuments);
        body.put("toolToken", toolToken);
        body.put("tools", tools);
        // 剩余时间不足时仍至少给一秒，避免负超时。
        long millis = Math.max(1000, Math.min(60000, remaining.toMillis()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/ai/tasks"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(millis))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 504) throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT");
            if (response.statusCode() != 200) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
            if (response.body().length > 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE");
            return json.readValue(response.body(), AiRules.PythonAnswer.class);
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
        } catch (java.io.IOException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
        } catch (ResponseStatusException mapped) {
            throw mapped;
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE");
        }
    }
}
