package com.auth.mapper;

import com.equipops.domain.entity.AlertRuleEntity;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 仅读取指定企业成员的角色，不使用全局 user_roles 作为企业授权。
 */
public interface AuthRoleMapper {
    /**
     * membershipId 是成员关系主键，不是 users.id。
     * 同时限定 tenant_id，避免读取当前成员在其他企业的角色；#{} 由 MyBatis 参数绑定。
     */
    @Select("""
            SELECT r.role_code
            FROM tenant_member_roles mr
            INNER JOIN roles r ON r.id = mr.role_id
            WHERE mr.tenant_id = #{tenantId}
              AND mr.membership_id = #{membershipId}
            ORDER BY r.role_code
            """)
    List<String> selectRoleCodes(@Param("tenantId") Long tenantId,
                                 @Param("membershipId") Long membershipId);


}
