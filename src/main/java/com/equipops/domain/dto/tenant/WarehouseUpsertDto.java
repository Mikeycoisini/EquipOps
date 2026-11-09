package com.equipops.domain.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 登记仓库的请求；tenantId 与 isDefault 不由客户端提交。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseUpsertDto {

    /** 所属厂区 ID，厂区必须属于当前企业且已启用。 */
    @NotNull(message = "所属厂区不能为空")
    @Positive(message = "所属厂区 ID 无效")
    private Long siteId;

    /** 仓库编号，在企业内唯一。 */
    @NotBlank(message = "仓库编号不能为空")
    @Size(max = 64, message = "仓库编号不能超过64个字符")
    private String warehouseCode;

    /** 仓库名称。 */
    @NotBlank(message = "仓库名称不能为空")
    @Size(max = 160, message = "仓库名称不能超过160个字符")
    private String name;

    /** 仓库位置，可为空。 */
    @Size(max = 255, message = "仓库位置不能超过255个字符")
    private String location;

    /** 是否启用；为空时按启用处理。 */
    private Boolean enabled;
}
