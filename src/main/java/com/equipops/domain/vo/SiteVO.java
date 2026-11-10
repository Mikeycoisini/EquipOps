package com.equipops.domain.vo;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Site 查询返回数据。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SiteVO {
    /** 厂区 ID。 */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Long id;

    /** 企业 ID。 */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Long tenantId;

    /** 厂区编号。 */
    private String siteCode;

    /** 厂区名称。 */
    private String name;

    /** 厂区地址。 */
    private String address;

    /** 是否启用。 */
    private Boolean enabled;

    /** 是否默认厂区。 */
    private Boolean isDefault;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
