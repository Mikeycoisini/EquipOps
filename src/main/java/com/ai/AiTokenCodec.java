package com.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 内部任务凭证：HS256、固定受众、短有效期，资料用途与工具用途互相隔离。 */
public class AiTokenCodec {
    private static final String AUDIENCE = "opsagent";
    private final byte[] secret;
    private final ObjectMapper json;
    private final Clock clock;

    public record Claims(long tenantId, long userId, String taskId, String jti,
                         long expiresAt, List<String> scopes, List<String> versionIds) {
    }

    public record ToolClaims(long tenantId, long userId, String taskId, String jti,
                             long expiresAt, Set<String> tools) {
        public ToolClaims {
            // 工具集合验证后不可修改。
            tools = Set.copyOf(tools);
        }
    }

    public AiTokenCodec(String secret, ObjectMapper json, Clock clock) {
        // 空密钥不阻止应用启动，但任何签发和验证都会被拒绝。
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        // JSON只用于内部声明。
        this.json = json;
        // 时钟可注入便于测试。
        this.clock = clock;
    }

    /** 资料回调用途的短期凭证。 */
    public String issue(long tenant, long user, String task, String scope, List<String> versionIds) {
        // 主键必须为正，版本集合来自Java任务快照。
        if (tenant <= 0 || user <= 0 || task == null || task.isBlank()
                || scope == null || !List.of("query", "index", "delete").contains(scope)
                || versionIds == null || versionIds.isEmpty() || versionIds.size() > 100) {
            throw invalid();
        }
        // 声明保持固定字段与短有效期。
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", AUDIENCE);
        claims.put("tenant_id", Long.toString(tenant));
        claims.put("user_id", Long.toString(user));
        claims.put("task_id", task);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("exp", clock.instant().getEpochSecond() + 120);
        claims.put("scope", List.of(scope));
        claims.put("version_ids", List.copyOf(versionIds));
        return signed(claims);
    }

    /** 工具网关专用凭证，只能调用列出的白名单工具。 */
    public String issueTools(long tenant, long user, String task, List<String> tools) {
        // 工具凭证绑定数字任务主键，便于数据库计数与重放仲裁。
        if (tenant <= 0 || user <= 0 || task == null || !task.matches("[1-9][0-9]{0,18}")
                || tools == null || tools.isEmpty() || tools.size() > 20) throw invalid();
        // 名称固定snake_case且不允许重复。
        Set<String> unique = new LinkedHashSet<>();
        for (String tool : tools) {
            if (tool == null || !tool.matches("[a-z][a-z0-9_]{0,63}") || !unique.add(tool)) throw invalid();
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", AUDIENCE);
        claims.put("tenant_id", Long.toString(tenant));
        claims.put("user_id", Long.toString(user));
        claims.put("task_id", task);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("exp", clock.instant().getEpochSecond() + 120);
        claims.put("scope", List.of("tools"));
        claims.put("tools", List.copyOf(unique));
        claims.put("version_ids", List.of());
        return signed(claims);
    }

    public Claims verify(String token) {
        try {
            // 公共校验覆盖签名、受众和有效期。
            JsonNode body = payload(token);
            return new Claims(positiveId(body, "tenant_id"), positiveId(body, "user_id"),
                    text(body, "task_id"), text(body, "jti"), body.get("exp").longValue(),
                    strings(body.get("scope"), 10), strings(body.get("version_ids"), 100));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public ToolClaims verifyTool(String token) {
        try {
            // 公共校验覆盖签名、受众和有效期。
            JsonNode body = payload(token);
            // 工具凭证只允许tools用途。
            List<String> scope = strings(body.get("scope"), 10);
            if (scope.size() != 1 || !"tools".equals(scope.get(0))) throw invalid();
            // 工具名必须非空且无重复。
            List<String> tools = strings(body.get("tools"), 20);
            Set<String> unique = new LinkedHashSet<>();
            for (String tool : tools) {
                if (!tool.matches("[a-z][a-z0-9_]{0,63}") || !unique.add(tool)) throw invalid();
            }
            // 任务号必须是数据库数字主键。
            String task = text(body, "task_id");
            if (!task.matches("[1-9][0-9]{0,18}")) throw invalid();
            return new ToolClaims(positiveId(body, "tenant_id"), positiveId(body, "user_id"),
                    task, text(body, "jti"), body.get("exp").longValue(), unique);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private JsonNode payload(String token) {
        // 密钥缺失时关闭授权。
        if (secret.length < 32) throw invalid();
        // 限制解码体积。
        if (token == null || token.length() > 8192) throw invalid();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) throw invalid();
        // 常量时间验签。
        if (!MessageDigest.isEqual(sign(parts[0] + "." + parts[1]), Base64.getUrlDecoder().decode(parts[2]))) throw invalid();
        JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
        if (!"HS256".equals(text(header, "alg"))) throw invalid();
        JsonNode body = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        if (!AUDIENCE.equals(text(body, "aud"))) throw invalid();
        JsonNode expiry = body.get("exp");
        long now = clock.instant().getEpochSecond();
        if (expiry == null || !expiry.isIntegralNumber() || !expiry.canConvertToLong()
                || expiry.longValue() <= now || expiry.longValue() > now + 600) throw invalid();
        return body;
    }

    private String signed(Map<String, Object> claims) {
        String header = encode(json.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String input = header + "." + encode(json.writeValueAsBytes(claims));
        return input + "." + encode(sign(input));
    }

    private byte[] sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception failure) {
            throw new IllegalStateException("AI_CREDENTIAL_UNAVAILABLE");
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private long positiveId(JsonNode body, String name) {
        String value = text(body, name);
        long id = Long.parseLong(value);
        if (id <= 0) throw invalid();
        return id;
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isString() || value.asString().length() > 512) throw invalid();
        return value.asString();
    }

    private List<String> strings(JsonNode value, int maximum) {
        if (value == null || !value.isArray() || value.size() > maximum) throw invalid();
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (item == null || !item.isString() || item.asString().length() > 512) throw invalid();
            result.add(item.asString());
        }
        return List.copyOf(result);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI_INVALID_CREDENTIAL");
    }
}
