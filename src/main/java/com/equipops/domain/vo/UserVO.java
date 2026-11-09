package com.equipops.domain.vo;

import java.time.LocalDateTime;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {
    /** 用户 ID */
    private Long id;

    /** 登录账号 */
    private String account;

    /** 用户显示名称 */
    private String displayName;

    /** 电子邮箱 */
    private String email;

    /** 联系电话 */
    private String phone;

    /** 是否启用 */
    private Boolean enabled;

    /** 上次登录时间 */
    private LocalDateTime lastLoginAt;

    /** 用户角色列表 */
    private Set<RoleVO> roles;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
