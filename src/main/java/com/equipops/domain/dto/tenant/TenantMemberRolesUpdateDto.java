package com.equipops.domain.dto.tenant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 企业成员请求；当前企业及操作者必须由服务端可信上下文取得。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantMemberRolesUpdateDto {
    /** 新的企业成员角色集合；空集合表示清除角色，是否允许由业务规则校验。 */
    @NotNull(message = "角色集合不能为空")
    @Size(max = 20, message = "角色数量不能超过 20 个")
    private Set<@NotNull @Positive Long> roleIds;
}
