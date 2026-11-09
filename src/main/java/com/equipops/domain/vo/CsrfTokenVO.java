package com.equipops.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 客户端写请求所需的 CSRF 信息，不是登录令牌。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CsrfTokenVO {
    /** 写请求应使用的请求头名称。 */
    private String headerName;

    /** 表单参数名称。 */
    private String parameterName;

    /** 当前 CSRF 令牌；登录和退出后须重新获取。 */
    private String token;
}
