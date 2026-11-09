package com.equipops.domain.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 登记或修改厂区的请求；tenantId 与 isDefault 不由客户端提交。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SiteUpsertDto {

    /** 厂区编号，在企业内唯一。 */
    @NotBlank(message = "厂区编号不能为空")
    @Size(max = 64, message = "厂区编号不能超过64个字符")
    private String siteCode;

    /** 厂区名称。 */
    @NotBlank(message = "厂区名称不能为空")
    @Size(max = 160, message = "厂区名称不能超过160个字符")
    private String name;

    /** 厂区地址，可为空。 */
    @Size(max = 255, message = "厂区地址不能超过255个字符")
    private String address;

    /** 是否启用；新增时为空按启用处理。 */
    private Boolean enabled;
}
