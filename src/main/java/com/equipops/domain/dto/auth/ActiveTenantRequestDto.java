package com.equipops.domain.dto.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 选择企业请求，仅表示选择意图，服务端仍须校验成员关系。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActiveTenantRequestDto {
    /** 拟进入的企业 ID。 */
    @NotNull(message = "企业 ID 不能为空")
    @Positive(message = "企业 ID 必须大于 0")
    private Long tenantId;
}
