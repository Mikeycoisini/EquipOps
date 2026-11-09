package com.equipops.domain.dto.tenant;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 企业成员请求；当前企业及操作者必须由服务端可信上下文取得。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantMemberStatusUpdateDto {
    /** 是否启用当前企业中的成员关系，不修改全局账号状态。 */
    @NotNull(message = "成员状态不能为空")
    private Boolean enabled;
}
