package com.equipops.domain.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 登录请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequestDto {

    /** 登录账号 */
    @NotBlank(message = "登录账号不能为空")
    @Size(max = 100, message = "登录账号不能超过 100 个字符")
    private String account;

    /** 登录密码 */
    @NotBlank(message = "登录密码不能为空")
    private String password;
}
