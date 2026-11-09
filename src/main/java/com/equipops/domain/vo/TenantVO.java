package com.equipops.domain.vo;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Tenant 查询返回数据。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantVO {
    /** 企业 ID。 */
    private Long id;

    /** 企业编号。 */
    private String tenantCode;

    /** 企业名称。 */
    private String name;

    /** 企业是否启用。 */
    private Boolean enabled;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
