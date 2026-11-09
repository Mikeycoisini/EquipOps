package com.equipops.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Session 登录结果；认证凭据由 HttpOnly 会话 Cookie 传递。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponseVO {
    /** 已认证的全局用户 ID。 */
    private Long userId;

    /** 登录账号。 */
    private String account;

    /** 用户显示名称。 */
    private String name;
}
