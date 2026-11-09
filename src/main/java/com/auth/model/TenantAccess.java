package com.auth.model;

import com.equipops.domain.entity.TenantEntity;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次验证的组合结果，便于将用户、企业、成员和角色一起返回给调用方。
 * 它不是数据库表，也不是客户端提交的 DTO；只能由服务器验证后组装。
 * 不将它长期放入 Session，以免成员撤销后仍沿用旧角色。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantAccess {
    /** 可信当前用户 ID。 */
    private Long userId;

    /** 已验证的当前企业。 */
    private TenantEntity tenant;

    /** 当前用户在该企业中的成员关系 ID。 */
    private Long membershipId;

    /** 该成员在当前企业中最新的角色编码。 */
    private Set<String> roleCodes;
}
