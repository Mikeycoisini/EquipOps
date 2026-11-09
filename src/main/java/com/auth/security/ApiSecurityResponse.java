package com.auth.security;

import com.common.response.Result;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 过滤器中的安全错误也使用与业务接口一致的 Result JSON。 */
@Component
@RequiredArgsConstructor
public class ApiSecurityResponse {
    /** 使用应用统一的 JSON 序列化器，避免自行拼接消息造成 JSON 转义错误。 */
    private final ObjectMapper objectMapper;

    /** HTTP 状态和 Result.code 保持一致，前端无需猜测“200 是否实际上失败”。 */
    public void write(HttpServletResponse response, int status, String message) throws IOException {
        // 响应类型、编码要在取得 Writer 并写入正文之前设置，确保中文消息正确输出。
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(status, message)));
    }
}
