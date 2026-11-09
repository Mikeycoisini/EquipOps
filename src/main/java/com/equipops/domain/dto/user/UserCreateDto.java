package com.equipops.domain.dto.user;

import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建用户请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserCreateDto {

    /** 登录账号 */
    @NotBlank(message = "登录账号不能为空")
    @Size(max = 100, message = "登录账号不能超过100个字符")
    private String account;

    /** 登录密码 */
    @NotBlank(message = "登录密码不能为空")
    private String password;

    /** 用户显示名称 */
    @NotBlank(message = "用户姓名不能为空")
    @Size(max = 50, message = "用户姓名不能超过50个字符")
    private String displayName;

    /** 电子邮箱 */
    private String email;

    /** 联系电话 */
    private String phone;

    /** 角色 ID 集合 */
    private Set<Long> roleIds;
}
