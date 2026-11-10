package com.equipops.domain.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** TenantMember 查询返回数据。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantMemberVO {
    /** 成员关系 ID，不是用户 ID。 */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Long id;

    /** 企业 ID。 */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Long tenantId;

    /** 全局用户 ID。 */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Long userId;

    /** 用户登录账号。 */
    private String account;

    /** 用户显示名称。 */
    private String displayName;

    /** 成员关系是否启用。 */
    private Boolean enabled;

    /** 用户在当前企业的角色，不使用全局 user_roles 代替。 */
    private List<RoleVO> roles;

    /** 加入企业时间。 */
    private LocalDateTime joinedAt;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
