package com.equipops.domain.vo;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户角色关联。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRoleVO {

    /** 用户 ID */
    private Long userId;

    /** 角色 ID */
    private Long roleId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
