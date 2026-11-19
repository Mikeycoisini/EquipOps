package com.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 浏览器请求只接受业务字段，身份与企业始终来自CurrentAccessService。 */
public final class AiRequests {
    private AiRequests() {
    }

    /** 提问请求；requestId用于幂等。 */
    public record Question(@NotBlank @Size(max = 64) String requestId,
                           @NotBlank @Size(max = 2000) String question,
                           List<Long> documentIds) {
        public Question {
            // 禁止请求体携带身份字段后通过反序列化注入。
            documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        }
    }
}
