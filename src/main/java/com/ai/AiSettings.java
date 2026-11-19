package com.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI链路配置；关闭时不创建外部调用，也不影响原业务。 */
@Data
@ConfigurationProperties(prefix = "ai")
public class AiSettings {
    /** 总开关，关闭时/api/ai/**返回503。 */
    private boolean enabled;
    /** Python服务地址，只允许本机或受控内网。 */
    private String baseUrl = "http://127.0.0.1:8090";
    /** 内部任务凭证共享密钥，生产必须由环境变量提供。 */
    private String sharedSecret;
    /** 资料受控根目录。 */
    private String docRoot = System.getProperty("user.home") + "/.equipops-v2/ai-documents";
    /** 单文件字节上限。 */
    private long maxBytes = 5 * 1024 * 1024;
    /** 任务总时长。 */
    private long taskTimeoutMs = 60000;
    /** 问题最大长度。 */
    private int maxQuestionLength = 2000;
}
