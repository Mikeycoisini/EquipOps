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
public class TenantMemberCreateDto {
    /** 拟加入当前企业的已有全局用户 ID。 */
    @NotNull(message = "用户 ID 不能为空")
    @Positive(message = "用户 ID 必须大于 0")
    private Long userId;

    /** 初始角色 ID；业务层须校验角色及授予权限。 */
    @NotNull(message = "角色集合不能为空")
    @Size(max = 20, message = "角色数量不能超过 20 个")
    private Set<@NotNull @Positive Long> roleIds;
}
