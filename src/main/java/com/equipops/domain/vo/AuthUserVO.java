package com.equipops.domain.vo;

import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 当前身份与当前企业摘要，不包含密码或会话凭据。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthUserVO {
    /** 全局用户 ID。 */
    private Long userId;

    /** 登录账号。 */
    private String account;

    /** 用户显示名称。 */
    private String name;

    /** 当前企业，尚未选择时为 null。 */
    private TenantVO activeTenant;

    /** 当前企业中的成员关系 ID，尚未选择时为 null。 */
    private Long membershipId;

    /** 当前企业中的最新角色编码，未选择企业时为空集合。 */
    private Set<String> roleCodes;
}
